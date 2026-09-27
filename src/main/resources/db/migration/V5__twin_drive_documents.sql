-- Configurations are durable; sampled simulation values belong to the bounded runtime cache.
CREATE TABLE twin_drive_documents (
 tenant_id text NOT NULL,
 project_id text PRIMARY KEY,
 revision bigint NOT NULL CHECK(revision > 0),
 config jsonb NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,project_id) REFERENCES projects(tenant_id,id) ON DELETE CASCADE,
 UNIQUE(tenant_id,project_id)
);
