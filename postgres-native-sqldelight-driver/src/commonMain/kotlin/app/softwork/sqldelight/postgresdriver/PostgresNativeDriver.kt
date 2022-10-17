package app.softwork.sqldelight.postgresdriver

import app.cash.sqldelight.*
import app.cash.sqldelight.db.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import libpq.*

public class PostgresNativeDriver(
    private val conn: CPointer<PGconn>,
    private val listenerSupport: ListenerSupport
) : SqlDriver {
    private var transaction: Transacter.Transaction? = null

    private val notifications: Flow<String>

    init {
        require(PQstatus(conn) == ConnStatusType.CONNECTION_OK) {
            conn.error()
        }
        setDateOutputs()

        notifications = when (listenerSupport) {
            is ListenerSupport.Local -> listenerSupport.notifications
            is ListenerSupport.Remote -> listenerSupport.remoteListener(conn)
            is ListenerSupport.None -> emptyFlow()
        }
    }

    private fun setDateOutputs() {
        execute(null, "SET intervalstyle = 'iso_8601';", 0)
        execute(null, "SET datestyle = 'ISO';", 0)
    }

    private val listeners = mutableMapOf<Query.Listener, Job>()

    private fun CoroutineScope.listen(queryKeysEscaped: List<String>, action: suspend (String) -> Unit) = launch {
        notifications.filter {
            it in queryKeysEscaped
        }.collect {
            action(it)
        }
    }

    override fun addListener(listener: Query.Listener, queryKeys: Array<String>) {
        when (listenerSupport) {
            ListenerSupport.None -> return
            is ListenerSupport.Local -> {
                listeners[listener] = listenerSupport.notificationScope.listen(queryKeys.toList()) {
                    listener.queryResultsChanged()
                }
            }

            is ListenerSupport.Remote -> {
                val queryKeysEscaped = queryKeys.map {
                    listenerSupport.notificationName(it)
                }
                listeners[listener] = listenerSupport.notificationScope.listen(queryKeysEscaped) {
                    listener.queryResultsChanged()
                }
                for (queryKey in queryKeysEscaped) {
                    execute(null, "LISTEN ${conn.escaped(queryKey)}", parameters = 0)
                }
            }
        }
    }

    override fun notifyListeners(queryKeys: Array<String>) {
        when (listenerSupport) {
            is ListenerSupport.Local -> {
                listenerSupport.notificationScope.launch {
                    for (queryKey in queryKeys) {
                        listenerSupport.notify(queryKey)
                    }
                }
            }

            is ListenerSupport.Remote -> {
                for (queryKey in queryKeys) {
                    val name = listenerSupport.notificationName(queryKey)
                    execute(null, "NOTIFY ${conn.escaped(name)}", parameters = 0)
                }
            }

            ListenerSupport.None -> return
        }
    }

    override fun removeListener(listener: Query.Listener, queryKeys: Array<String>) {
        val queryListeners = listeners[listener]
        if (queryListeners != null) {
            if (listenerSupport is ListenerSupport.Remote) {
                for (queryKey in queryKeys) {
                    val name = listenerSupport.notificationName(queryKey)
                    execute(null, "UNLISTEN ${conn.escaped(name)}", parameters = 0)
                }
            }
            queryListeners.cancel()
            listeners.remove(listener)
        }
    }

    override fun currentTransaction(): Transacter.Transaction? = transaction

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult.Value<Long> {
        val preparedStatement = if (parameters != 0) PostgresPreparedStatement(parameters).apply {
            if (binders != null) {
                binders()
            }
        } else null
        val result = if (identifier != null) {
            if (!preparedStatementExists(identifier)) {
                PQprepare(
                    conn,
                    stmtName = identifier.toString(),
                    query = sql,
                    nParams = parameters,
                    paramTypes = preparedStatement?.types?.refTo(0)
                ).check(conn).clear()
            }
            memScoped {
                PQexecPrepared(
                    conn,
                    stmtName = identifier.toString(),
                    nParams = parameters,
                    paramValues = preparedStatement?.values(this),
                    paramFormats = preparedStatement?.formats?.refTo(0),
                    paramLengths = preparedStatement?.lengths?.refTo(0),
                    resultFormat = TEXT_RESULT_FORMAT
                )
            }
        } else {
            memScoped {
                PQexecParams(
                    conn,
                    command = sql,
                    nParams = parameters,
                    paramValues = preparedStatement?.values(this),
                    paramFormats = preparedStatement?.formats?.refTo(0),
                    paramLengths = preparedStatement?.lengths?.refTo(0),
                    resultFormat = TEXT_RESULT_FORMAT,
                    paramTypes = preparedStatement?.types?.refTo(0)
                )
            }
        }.check(conn)

        return QueryResult.Value(value = result.rows)
    }

    private val CPointer<PGresult>.rows: Long
        get() {
            val rows = PQcmdTuples(this)!!.toKString()
            clear()
            return rows.toLongOrNull() ?: 0
        }

    private fun preparedStatementExists(identifier: Int): Boolean {
        val result =
            executeQuery(null, "SELECT name FROM pg_prepared_statements WHERE name = $1", parameters = 1, binders = {
                bindString(0, identifier.toString())
            }, mapper = {
                it.next()
                it.getString(0)
            })
        return result.value != null
    }

    private fun Int.escapeNegative(): String = if (this < 0) "_${toString().substring(1)}" else toString()

    private fun preparedStatement(
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): PostgresPreparedStatement? = if (parameters != 0) {
        PostgresPreparedStatement(parameters).apply {
            if (binders != null) {
                binders()
            }
        }
    } else null

    public fun <R> executeQueryWithNativeCursor(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> R,
        parameters: Int,
        fetchSize: Int = 1,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult.Value<R> {
        val cursorName = if (identifier == null) "myCursor" else "cursor${identifier.escapeNegative()}"
        val cursor = "DECLARE $cursorName CURSOR FOR"

        val preparedStatement = preparedStatement(parameters, binders)
        val result = if (identifier != null) {
            checkPreparedStatement(identifier, "$cursor $sql", parameters, preparedStatement)
            conn.exec("BEGIN")
            memScoped {
                PQexecPrepared(
                    conn,
                    stmtName = identifier.toString(),
                    nParams = parameters,
                    paramValues = preparedStatement?.values(this),
                    paramLengths = preparedStatement?.lengths?.refTo(0),
                    paramFormats = preparedStatement?.formats?.refTo(0),
                    resultFormat = TEXT_RESULT_FORMAT
                )
            }
        } else {
            conn.exec("BEGIN")
            memScoped {
                PQexecParams(
                    conn,
                    command = "$cursor $sql",
                    nParams = parameters,
                    paramValues = preparedStatement?.values(this),
                    paramLengths = preparedStatement?.lengths?.refTo(0),
                    paramFormats = preparedStatement?.formats?.refTo(0),
                    paramTypes = preparedStatement?.types?.refTo(0),
                    resultFormat = TEXT_RESULT_FORMAT
                )
            }
        }.check(conn)

        val value = RealCursor(result, cursorName, conn, fetchSize).use(mapper)
        return QueryResult.Value(value = value)
    }

    private fun checkPreparedStatement(
        identifier: Int,
        sql: String,
        parameters: Int,
        preparedStatement: PostgresPreparedStatement?
    ) {
        if (!preparedStatementExists(identifier)) {
            PQprepare(
                conn,
                stmtName = identifier.toString(),
                query = sql,
                nParams = parameters,
                paramTypes = preparedStatement?.types?.refTo(0)
            ).check(conn).clear()
        }
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> R,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?
    ): QueryResult.Value<R> {
        val preparedStatement = preparedStatement(parameters, binders)
        val result = if (identifier != null) {
            checkPreparedStatement(identifier, sql, parameters, preparedStatement)
            memScoped {
                PQexecPrepared(
                    conn,
                    stmtName = identifier.toString(),
                    nParams = parameters,
                    paramValues = preparedStatement?.values(this),
                    paramLengths = preparedStatement?.lengths?.refTo(0),
                    paramFormats = preparedStatement?.formats?.refTo(0),
                    resultFormat = TEXT_RESULT_FORMAT
                )
            }
        } else {
            memScoped {
                PQexecParams(
                    conn,
                    command = sql,
                    nParams = parameters,
                    paramValues = preparedStatement?.values(this),
                    paramLengths = preparedStatement?.lengths?.refTo(0),
                    paramFormats = preparedStatement?.formats?.refTo(0),
                    paramTypes = preparedStatement?.types?.refTo(0),
                    resultFormat = TEXT_RESULT_FORMAT
                )
            }
        }.check(conn)

        val value = NoCursor(result).use(mapper)
        return QueryResult.Value(value = value)
    }

    internal companion object {
        const val TEXT_RESULT_FORMAT = 0
        const val BINARY_RESULT_FORMAT = 1
    }

    override fun close() {
        PQfinish(conn)
        for ((_, job) in listeners) {
            job.cancel()
        }
    }

    override fun newTransaction(): QueryResult.Value<Transacter.Transaction> {
        conn.exec("BEGIN")
        return QueryResult.Value(Transaction(transaction))
    }

    private inner class Transaction(
        override val enclosingTransaction: Transacter.Transaction?
    ) : Transacter.Transaction() {
        override fun endTransaction(successful: Boolean): QueryResult.Unit {
            if (enclosingTransaction == null) {
                if (successful) {
                    conn.exec("END")
                } else {
                    conn.exec("ROLLBACK")
                }
            }
            transaction = enclosingTransaction
            return QueryResult.Unit
        }
    }

    public fun copy(stdin: String): Long {
        val status = PQputCopyData(conn, stdin, stdin.encodeToByteArray().size)
        check(status == 1) {
            conn.error()
        }
        val end = PQputCopyEnd(conn, null)
        check(end == 1) {
            conn.error()
        }
        val result = PQgetResult(conn).check(conn)
        return result.rows
    }
}

private fun CPointer<PGconn>?.error(): String {
    val errorMessage = PQerrorMessage(this)!!.toKString()
    PQfinish(this)
    return errorMessage
}

internal fun CPointer<PGresult>?.clear() {
    PQclear(this)
}

internal fun CPointer<PGconn>.exec(sql: String) {
    val result = PQexec(this, sql)
    result.check(this)
    result.clear()
}

internal fun CPointer<PGresult>?.check(conn: CPointer<PGconn>): CPointer<PGresult> {
    val status = PQresultStatus(this)
    check(status == PGRES_TUPLES_OK || status == PGRES_COMMAND_OK || status == PGRES_COPY_IN) {
        conn.error()
    }
    return this!!
}

private fun CPointer<PGconn>.escaped(value: String): String {
    val cString = PQescapeIdentifier(this, value, value.length.convert())
    val escaped = cString!!.toKString()
    PQfreemem(cString)
    return escaped
}

public fun PostgresNativeDriver(
    host: String, database: String, user: String, password: String, port: Int = 5432, options: String? = null,
    listenerSupport: ListenerSupport = ListenerSupport.None
): PostgresNativeDriver {
    val conn = PQsetdbLogin(
        pghost = host,
        pgport = port.toString(),
        pgtty = null,
        dbName = database,
        login = user,
        pwd = password,
        pgoptions = options
    )
    require(PQstatus(conn) == ConnStatusType.CONNECTION_OK) {
        conn.error()
    }
    return PostgresNativeDriver(conn!!, listenerSupport = listenerSupport)
}
