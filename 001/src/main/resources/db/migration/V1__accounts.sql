CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    code_hash VARCHAR(255),
    code_expires_at TIMESTAMP WITH TIME ZONE,
    code_sent_at TIMESTAMP WITH TIME ZONE,
    code_attempts INTEGER NOT NULL DEFAULT 0
);
