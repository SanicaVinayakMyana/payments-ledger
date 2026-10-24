package com.sanica.ledger;

import java.util.Map;

/** Twelve-factor configuration read from environment variables. */
public record Config(int port, String jdbcUrl, String dbUser, String dbPassword, int dbPoolSize, String redisUrl) {

    public static Config fromEnv() {
        return fromMap(System.getenv());
    }

    static Config fromMap(Map<String, String> env) {
        String redisUrl = env.get("REDIS_URL");
        return new Config(
                Integer.parseInt(env.getOrDefault("PORT", "8080")),
                env.getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/ledger"),
                env.getOrDefault("DB_USER", "ledger"),
                env.getOrDefault("DB_PASSWORD", "ledger"),
                Integer.parseInt(env.getOrDefault("DB_POOL_SIZE", "20")),
                redisUrl == null || redisUrl.isBlank() ? null : redisUrl);
    }
}
