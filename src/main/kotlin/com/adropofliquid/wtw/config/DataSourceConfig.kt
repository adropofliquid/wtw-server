package com.adropofliquid.wtw.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.net.URI
import javax.sql.DataSource

/**
 * Turns Dokku's `DATABASE_URL` (`postgres://user:pass@host:port/db`) into a Hikari pool.
 * Spring Boot's Flyway and JPA auto-configuration use this bean as they would the default one.
 */
@Configuration
class DataSourceConfig {
    @Bean
    @Primary
    fun dataSource(props: WtwProperties): DataSource {
        val jdbc = JdbcUrl.fromDatabaseUrl(props.databaseUrl)
        val cfg = HikariConfig().apply {
            jdbcUrl = jdbc.url
            username = jdbc.user
            password = jdbc.password
            maximumPoolSize = props.dbPoolSize
            poolName = "wtw"
        }
        return HikariDataSource(cfg)
    }
}

data class JdbcUrl(val url: String, val user: String?, val password: String?) {
    companion object {
        /** Accepts `postgres://`, `postgresql://` or an already-JDBC `jdbc:postgresql://` URL. */
        fun fromDatabaseUrl(raw: String): JdbcUrl {
            if (raw.startsWith("jdbc:")) return JdbcUrl(raw, null, null)
            val uri = URI(raw.replaceFirst(Regex("^postgres(ql)?://"), "http://"))
            val (user, pass) = (uri.rawUserInfo ?: "").split(":", limit = 2).let {
                java.net.URLDecoder.decode(it.getOrElse(0) { "" }, Charsets.UTF_8) to
                    java.net.URLDecoder.decode(it.getOrElse(1) { "" }, Charsets.UTF_8)
            }
            val port = if (uri.port == -1) 5432 else uri.port
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            return JdbcUrl("jdbc:postgresql://${uri.host}:$port${uri.rawPath}$query", user.ifEmpty { null }, pass.ifEmpty { null })
        }
    }
}
