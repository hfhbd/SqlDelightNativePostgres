# Module postgres-native-sqldelight

A native Postgres driver using libpq.

You can use the driver with [SQLDelight](https://github.com/cashapp/sqldelight), but this is not required.

- [Source code](https://github.com/hfhbd/postgres-native-sqldelight)

## Install

You need `libpq` installed and available in your `$PATH`.

This package is uploaded to MavenCentral and supports macOS and linuxX64.
Windows is currently not supported.

````kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("app.softwork:postgres-native-sqldelight-driver:LATEST")
}

sqldelight {
    database("NativePostgres") {
        dialect("app.softwork:postgres-native-sqldelight-dialect:LATEST")
    }
    linkSqlite = false
}
````

## Usage

```kotlin
val driver = PostgresNativeDriver(
    host = "localhost",
    port = 5432,
    user = "postgres",
    database = "postgres",
    password = "password",
    options = null,
    listenerSupport = ListenerSupport.Remote(coroutineScope)
)
```

This driver supports local and remote listeners.
Local listeners only notify this client, ideally for testing or using the database with only one client at a time with
SQLDelight only.
Remote listener support uses `NOTIFY` and `LISTEN`, so you can use this with multiple clients or with existing database
triggers.
SQLDelight uses and expects the table name as payload, but you can provide a mapper function.

### SQLDelight Support

Just create the driver and use your database instances in the usual way.

### Raw usage

Beside SQLDelight you could also use this driver with raw queries.
The identifier is used to reuse prepared statements.

```kotlin
driver.execute(identifier = null, sql = "INSERT INTO foo VALUES (42)", parameters = 0, binders = null)
```

It also supports a real lazy cursor or a Flow. The `fetchSize` parameter defines how many rows are fetched at once:

```kotlin
val names: List<Simple> = driver.executeQueryWithNativeCursor(
    identifier = null,
    sql = "SELECT index, name, bytes FROM foo",
    mapper = { cursor ->
        // You need to call `next` and use the cursor to return your type, here it is a list.
        
        // Important, don't leak this cursor, eg by returning a Sequence,
        // otherwise the cursor will be closed before fetching rows.
        // If you need to use an async iterator, use the `Flow` overload, `executeQueryAsFlow`.
        buildList {
            while (cursor.next()) {
                add(
                    Simple(
                        index = cursor.getLong(0)!!.toInt(),
                        name = cursor.getString(1),
                        byteArray = cursor.getBytes(2)
                    )
                )
            }
        }
    },
    parameters = 0,
    fetchSize = 100,
    binders = null
)

val namesFlow: Flow<Simple> = driver.executeQueryAsFlow(
    identifier = null,
    sql = "SELECT index, name, bytes FROM foo",
    mapper = { cursor ->
        Simple(
            index = cursor.getLong(0)!!.toInt(),
            name = cursor.getString(1),
            byteArray = cursor.getBytes(2)
        )
    },
    parameters = 0,
    fetchSize = 100,
    binders = null
)
```

And for bulk imports, use the `copy` method. You need to enable `COPY` first:

```kotlin
driver.execute(514394779, "COPY foo FROM STDIN (FORMAT CSV)", 0)
val rows = driver.copy("1,2,3\n4,5,6\n")
```

## License

Apache 2

## Contributing

You need libpq installed: https://formulae.brew.sh/formula/libpq#default

You have to add the compiler flags to your path too.
The exact commands depend on your config, but you will get them during installing libpq with homebrew.

Sample commands:

```
If you need to have libpq first in your PATH, run:
  echo 'export PATH="/home/linuxbrew/.linuxbrew/opt/libpq/bin:$PATH"' >> /home/runner/.bash_profile
For compilers to find libpq you may need to set:
  export LDFLAGS="-L/home/linuxbrew/.linuxbrew/opt/libpq/lib"
  export CPPFLAGS="-I/home/linuxbrew/.linuxbrew/opt/libpq/include"
```

### Testing

If you install libpq with homebrew, it will install the platform-specific artifact.

| Host        | Supported test targets |
|-------------|------------------------|
| linux x64   | linux x64              |
| macOS x64   | macOS x64, linux x64   |
| macOS arm64 | macOS arm64            |
