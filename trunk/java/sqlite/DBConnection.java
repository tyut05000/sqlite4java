package sqlite;

import sqlite.internal.*;
import static sqlite.internal.SQLiteConstants.*;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * DBConnection is a single connection to sqlite database. Most methods are thread-confined,
 * and will throw errors if called from alien thread. Confinement thread is defined at the
 * construction time.
 * <p/>
 * DBConnection should be expicitly closed before the object is disposed. Failing to do so
 * may result in unpredictable behavior from sqlite.
 */
public final class DBConnection {
  /**
   * The database file, or null if it is memory database
   */
  private final File myFile;
  private final Thread myConfinement;
  private final int myNumber = DBInternal.nextConnectionNumber();
  private final Object myLock = new Object();

  /**
   * Handle to the db. Almost confined: usually not changed outside the confining thread, except for close() method.
   */
  private SWIGTYPE_p_sqlite3 myHandle;
  private int myOpenCounter;

  /**
   * Prepared statements. Almost confined.
   */
  private final Map<String, DBStatement> myStatementCache = new HashMap<String, DBStatement>();
  private final List<DBStatement> myStatements = new ArrayList<DBStatement>();

  /**
   * Create connection to database located in the specified file.
   * Database is not opened by this method, but the whole object is being confined to
   * the calling thread. So call the constructor only in the thread which will be used
   * to work with the connection.
   *
   * @param dbfile database file, or null for memory database
   */
  public DBConnection(File dbfile) {
    myFile = dbfile;
    myConfinement = Thread.currentThread();
    DBInternal.logger.info(this + " created(" + myFile + "," + myConfinement + ")");
  }

  /**
   * Create connection to in-memory temporary database.
   *
   * @see #DBConnection(java.io.File)
   */
  public DBConnection() {
    this(null);
  }

  /**
   * @return the file hosting the database, or null if database is in memory
   */
  public File getDatabaseFile() {
    return myFile;
  }

  public boolean isMemoryDatabase() {
    return myFile == null;
  }

  /**
   * Opens database, creating it if needed.
   *
   * @see #open(boolean)
   */
  public DBConnection open() throws DBException {
    return open(true);
  }

  /**
   * Opens database. If database is already open, fails gracefully, allowing process
   * to continue in production mode.
   *
   * @param allowCreate if true, database file may be created. For in-memory database, must
   *                    be true
   */
  public DBConnection open(boolean allowCreate) throws DBException {
    int flags = Open.SQLITE_OPEN_READWRITE;
    if (!allowCreate) {
      if (isMemoryDatabase()) {
        throw new DBException(Wrapper.WRAPPER_WEIRD, "cannot open memory database without creation");
      }
    } else {
      flags |= Open.SQLITE_OPEN_CREATE;
    }
    openX(flags);
    return this;
  }

  /**
   * Opens database is read-only mode. Not applicable for in-memory database.
   */
  public DBConnection openReadonly() throws DBException {
    if (isMemoryDatabase()) {
      throw new DBException(Wrapper.WRAPPER_WEIRD, "cannot open memory database in read-only mode");
    }
    openX(Open.SQLITE_OPEN_READONLY);
    return this;
  }

  /**
   * Tells whether database is open. May be called from another thread.
   */
  public boolean isOpen() {
    synchronized (myLock) {
      return myHandle != null;
    }
  }

  boolean isOpen(int openCounter) {
    synchronized (myLock) {
      return myHandle != null && myOpenCounter == openCounter;
    }
  }

  /**
   * Closes database. After database is closed, it may be reopened again. In case of in-memory
   * database, the reopened database will be empty.
   * <p/>
   * This method may be called from another thread.
   */
  public void close() {
    SWIGTYPE_p_sqlite3 handle;
    DBStatement[] statements = null;
    synchronized (myLock) {
      handle = myHandle;
      if (handle == null)
        return;
      myHandle = null;
      statements = getStatementsForDisposeOnClose(statements);
    }
    disposeStatements(statements);
    int rc = SQLiteSwigged.sqlite3_close(handle);
    // rc may be SQLiteConstants.Result.SQLITE_BUSY if statements are open
    if (rc != SQLiteConstants.Result.SQLITE_OK) {
      String errmsg = null;
      try {
        errmsg = SQLiteSwigged.sqlite3_errmsg(handle);
      } catch (Exception e) {
        DBInternal.logger.log(Level.WARNING, "cannot get sqlite3_errmsg", e);
      }
      DBInternal.logger.warning(this + " close error " + rc + (errmsg == null ? "" : ": " + errmsg));
    }
    DBInternal.logger.info(this + " closed");
  }

  public DBConnection exec(String sql) throws DBException {
    checkThread();
    String[] error = {null};
    int rc = SQLiteManual.sqlite3_exec(handle(), sql, error);
    throwResult(rc, "exec()", error[0]);
    return this;
  }

  public DBStatement prepare(String sql) throws DBException {
    return prepare(sql, true);
  }

  public DBStatement prepare(String sql, boolean useCache) throws DBException {
    checkThread();
    SWIGTYPE_p_sqlite3 handle;
    int openCounter;
    DBStatement statement = null;
    synchronized (myLock) {
      if (useCache) {
        statement = myStatementCache.get(sql);
      }
      handle = handle();
      openCounter = myOpenCounter;
    }
    if (statement != null) {
      return validateCachedStatement(statement);
    }
    int[] rc = {Integer.MIN_VALUE};
    SWIGTYPE_p_sqlite3_stmt stmt = SQLiteManual.sqlite3_prepare_v2(handle, sql, rc);
    throwResult(rc[0], "prepare()", sql);
    if (stmt == null)
      throw new DBException(Wrapper.WRAPPER_WEIRD, "sqlite did not return stmt");
    synchronized (myLock) {
      // the connection may close while prepare in progress
      // most probably that would throw DBException earlier, but we'll check anyway
      if (myHandle != null && myOpenCounter == openCounter) {
        statement = new DBStatement(this, stmt, sql, openCounter);
        myStatements.add(statement);
        if (useCache) {
          myStatementCache.put(sql, statement);
        }
      }
    }
    if (statement == null) {
      // connection closed
      try {
        throwResult(SQLiteSwigged.sqlite3_finalize(stmt), "finalize() in prepare()");
      } catch (Exception e) {
        // ignore
      }
      throw new DBException(Wrapper.WRAPPER_NOT_OPENED, "connection closed while prepare() was in progress");
    }
    return statement;
  }

  private DBStatement validateCachedStatement(DBStatement statement) throws DBException {
    boolean hasRow = statement.hasRow();
    boolean hasBindings = statement.hasBindings();
    if (hasRow || hasBindings) {
      String msg = hasRow ? (hasBindings ? "rows and bindings" : "rows") : "bindings";
      msg = statement + ": retrieved from cache with " + msg + ", clearing";

      // todo not sure if we need stack trace in log files here
//            IllegalStateException thrown = new IllegalStateException(msg);
      IllegalStateException thrown = null;
      DBInternal.logger.log(Level.WARNING, msg, thrown);
      statement.clear();
    }
    return statement;
  }

  private void disposeStatements(DBStatement[] statements) {
    if (statements != null) {
      for (DBStatement statement : statements) {
        try {
          statement.dispose();
        } catch (DBException e) {
          DBInternal.logger.log(Level.WARNING, "dispose(" + statement + ") during close()", e);
        }
      }
    }
    synchronized (myLock) {
      if (!myStatements.isEmpty()) {
        DBInternal.recoverableError(this, "not all statements disposed (" + myStatements + ")", false);
        myStatements.clear();
      }
      myStatementCache.clear();
    }
  }

  private DBStatement[] getStatementsForDisposeOnClose(DBStatement[] statements) {
    if (!myStatements.isEmpty()) {
      if (myConfinement == Thread.currentThread()) {
        statements = myStatements.toArray(new DBStatement[myStatements.size()]);
      } else {
        DBInternal.logger.warning(this + " cannot clear " + myStatements.size() + " statements when closing from alien threads");
      }
    }
    return statements;
  }

  void statementDisposed(DBStatement statement, String sql) {
    synchronized (myLock) {
      if (!myStatements.remove(statement)) {
        DBInternal.recoverableError(statement, "unknown statement disposed", true);
      }
      DBStatement removed = myStatementCache.remove(sql);
      if (removed != null && removed != statement) {
        // statement wasn't cached, but another statement with the same sql was cached
        myStatementCache.put(sql, removed);
      }
    }
  }

  private SWIGTYPE_p_sqlite3 handle() throws DBException {
    synchronized (myLock) {
      SWIGTYPE_p_sqlite3 handle = myHandle;
      if (handle == null)
        throw new DBException(Wrapper.WRAPPER_NOT_OPENED, null);
      return handle;
    }
  }

  void throwResult(int resultCode, String operation) throws DBException {
    throwResult(resultCode, operation, null);
  }

  void throwResult(int resultCode, String operation, Object additional) throws DBException {
    if (resultCode != SQLiteConstants.Result.SQLITE_OK) {
      // ignore sync
      SWIGTYPE_p_sqlite3 handle = myHandle;
      String message = this + " " + operation;
      String additionalMessage = additional == null ? null : String.valueOf(additional);
      if (additionalMessage != null)
        message += " " + additionalMessage;
      if (handle != null) {
        try {
          String errmsg = SQLiteSwigged.sqlite3_errmsg(handle);
          if (additionalMessage == null || !additionalMessage.equals(errmsg)) {
            message += " [" + errmsg + "]";
          }
        } catch (Exception e) {
          DBInternal.logger.log(Level.WARNING, "cannot get sqlite3_errmsg", e);
        }
      }
      throw new DBException(resultCode, message);
    }
  }

  private void openX(int flags) throws DBException {
    DBGlobal.loadLibrary();
    checkThread();
    SWIGTYPE_p_sqlite3 handle;
    synchronized (myLock) {
      handle = myHandle;
    }
    if (handle != null) {
      DBInternal.recoverableError(this, "already opened", true);
      return;
    }
    String dbname = getSqliteDbName();
    int[] rc = {Integer.MIN_VALUE};
    handle = SQLiteManual.sqlite3_open_v2(dbname, flags, rc);
    if (rc[0] != Result.SQLITE_OK) {
      if (handle != null) {
        try {
          SQLiteSwigged.sqlite3_close(handle);
        } catch (Exception e) {
          // ignore
        }
      }
      String errorMessage = SQLiteSwigged.sqlite3_errmsg(null);
      throw new DBException(rc[0], errorMessage);
    }
    if (handle == null) {
      throw new DBException(Wrapper.WRAPPER_WEIRD, "sqlite didn't return db handle");
    }
    synchronized (myLock) {
      myOpenCounter++;
      myHandle = handle;
    }
    DBInternal.logger.info(this + " opened(" + flags + ")");
  }

  private String getSqliteDbName() {
    return myFile == null ? ":memory:" : myFile.getAbsolutePath();
  }

  int getStatementCount() {
    synchronized (myLock) {
      return myStatements.size();
    }
  }

  void checkThread() throws DBException {
    Thread thread = Thread.currentThread();
    if (thread != myConfinement) {
      String message = this + " confined(" + myConfinement + ") used(" + thread + ")";
      throw new DBException(Wrapper.WRAPPER_CONFINEMENT_VIOLATED, message);
    }
  }

  public String toString() {
    return "sqlite[" + myNumber + "]";
  }

  protected void finalize() throws Throwable {
    super.finalize();
    SWIGTYPE_p_sqlite3 handle = myHandle;
    if (handle != null) {
      DBInternal.recoverableError(this, "wasn't closed before disposal", true);
      try {
        close();
      } catch (Throwable e) {
        // ignore
      }
    }
  }
}