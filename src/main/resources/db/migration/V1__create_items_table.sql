CREATE TABLE items (
    id          BIGINT        AUTO_INCREMENT PRIMARY KEY,
    url         VARCHAR(2048) NOT NULL,
    create_time BIGINT        NOT NULL,
    UNIQUE KEY url_unique (url(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
