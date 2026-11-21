ALTER TABLE t_agent_memory_cursor
    ADD COLUMN IF NOT EXISTS memory_version bigint NOT NULL DEFAULT 0;
