package com.adropofliquid.wtw.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.jdbc.core.JdbcTemplate

/**
 * The OLD server's Postgres, for the one-off data copy only (brief 7.1). Read-only on every connection.
 *
 * Deliberately NOT a `DataSource` or `JdbcTemplate` bean: a second bean of those types would switch off Boot's
 * default JdbcTemplate and add the old DB to `/health`. The primary DataSource in [DataSourceConfig] stays the
 * only one that JPA, Flyway and health see.
 */
class OldDatabase(val dataSource: HikariDataSource) : DisposableBean {
    /** Plain SELECTs against the old schema (it has no entities here). */
    val jdbc = JdbcTemplate(dataSource)
    override fun destroy() = dataSource.close()
}

/** True when `--data-copy` is on the command line or OLD_DB_URL is set. Normal boot without either never creates the bean. */
class OldDatabaseWanted : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val env = context.environment
        return env.containsProperty("data-copy") || !env.getProperty("wtw.old.db-url").isNullOrBlank()
    }
}

@Configuration
class OldDataSourceConfig {
    @Bean(destroyMethod = "destroy")
    @Conditional(OldDatabaseWanted::class)
    fun oldDatabase(props: WtwProperties): OldDatabase {
        val o = props.old
        check(o.dbUrl.isNotBlank()) { "OLD_DB_URL is required for --data-copy" }
        val jdbc = JdbcUrl.fromDatabaseUrl(o.dbUrl)
        val cfg = HikariConfig().apply {
            jdbcUrl = jdbc.url
            username = o.dbUser.ifBlank { jdbc.user ?: "" }
            password = o.dbPass.ifBlank { jdbc.password ?: "" }
            poolName = "wtw-old"
            maximumPoolSize = 4
            minimumIdle = 0
            // Read-only, three ways: the JDBC flag, the session default, and the server-side option.
            isReadOnly = true
            connectionInitSql = "SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY"
            addDataSourceProperty("options", "-c default_transaction_read_only=on")
            // Do not fail boot if the old DB is down; the copy fails on first use instead.
            initializationFailTimeout = -1
        }
        return OldDatabase(HikariDataSource(cfg))
    }
}
