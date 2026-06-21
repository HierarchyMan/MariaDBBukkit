> **⚠️ Heads up:** This project is heavily vibe coded. I'm building it as I go, testing and tweaking things as I figure out what actually works. Once I've had time to properly architect it and feel genuinely proud of the codebase, I'll remove this warning.

# MariaDBBukkit

An embedded MariaDB server for Bukkit/Paper Minecraft servers. 

The whole point of this plugin is to save you the headache of setting up a separate MySQL or MariaDB server. 

**Here's how easy it is:**
1. Drop the `MariaDBBukkit.jar` into your `plugins/` folder and restart your server. It automatically sets up a real SQL database in the background.
2. Go to **ANY third-party plugin** that needs a MySQL database (like LuckPerms, CoreProtect, LiteBans, etc.).
3. Simply enter the host (usually `localhost`), port, database name, username, and password configured in `plugins/MariaDBBukkit/config.yml`.

That's it! It works exactly like a regular MySQL server for your other plugins, with zero external installation or setup required.

## What it does

- Starts an embedded MariaDB server when your Minecraft server boots up
- Downloads the native binaries automatically on first run (from Maven Central)
- Creates the database and user for you — just configure what you want in `config.yml`
- Acts as a drop-in MySQL replacement for any third-party plugin
- Exposes a HikariCP connection pool that other plugins can use through Bukkit's ServicesManager
- Gives you a `/mariadbbukkit` command to check status, restart, or stop the database without restarting the whole server

## For server admins

Drop the jar into `plugins/`, edit `plugins/MariaDBBukkit/config.yml` if you want to change anything (database name, port, etc.), and restart. It'll download MariaDB binaries on first startup — might take a minute depending on your connection.

The default config works fine for most setups. You probably want to change the password from `root_password`.

### Commands

| Command | Description | Permission |
|---|---|---|
| `/mariadbbukkit` or `/mariadbbukkit status` | Shows if the DB is running, port, database name, JDBC URL | Everyone |
| `/mariadbbukkit restart` | Stops and restarts the embedded MariaDB | `mariadbbukkit.admin` (op) |
| `/mariadbbukkit stop` | Disables the plugin (stops MariaDB) | `mariadbbukkit.admin` (op) |

### Configuration

The defaults in `config.yml` should work out of the box. Key settings:

- `database.name` — schema name (default: `minecraft`)
- `database.port` — TCP port (default: `3306`, use `0` for auto)
- `database.username` / `database.password` — credentials for connecting plugins
- `download.mariadb-version` — MariaDB version to fetch (default: `11.4.5`)
- `security.skip-grant-tables` — default `true`, fine for localhost-only use

## For plugin developers

MariaDBBukkit registers itself as a Bukkit service. Getting a connection is straightforward:

```java
var reg = Bukkit.getServicesManager().getRegistration(MariaDBService.class);
if (reg != null) {
    MariaDBService svc = reg.getProvider();
    try (Connection conn = svc.getConnection()) {
        // do your thing
    }
}
```

You can also grab the JDBC URL directly if you need it:

```java
String jdbcUrl = svc.getJdbcUrl();
String dbName = svc.getDatabaseName();
int port = svc.getPort();
```

The connection pool is HikariCP — prepared statement caching and all that is already configured.

## Platform support

| Platform | Status |
|---|---|
| Linux x86_64 | Auto-downloads binaries |
| Linux aarch64 | Set `download.source-url` in config, or pre-place binaries |
| Windows x86_64 | Auto-downloads binaries |
| macOS ARM64 | Auto-downloads binaries |
| macOS x86_64 | Auto-downloads binaries |
| Termux (Android) | Uses native `pkg install mariadb` — auto-detected |

## Building from source

Requires Java 21 and Maven.

```
mvn clean package
```

The shaded jar will be in `target/`.

## License

GPL-3.0 — use it, fork it, modify it, just keep it open source if you distribute changes.
