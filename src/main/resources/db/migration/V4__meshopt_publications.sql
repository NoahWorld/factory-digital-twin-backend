ALTER TABLE resources ADD COLUMN source_model_id text REFERENCES resources(id) ON DELETE RESTRICT;
ALTER TABLE resources ADD COLUMN compression jsonb;
CREATE INDEX resources_source_model ON resources(source_model_id);

CREATE TABLE publication_versions (
  id text PRIMARY KEY,
  tenant_id text NOT NULL,
  project_id text NOT NULL,
  document_revision bigint NOT NULL,
  title text NOT NULL,
  snapshot jsonb NOT NULL,
  created_by text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id,project_id) REFERENCES projects(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id,created_by) REFERENCES users(tenant_id,id),
  UNIQUE (tenant_id,project_id,id)
);
CREATE INDEX publication_versions_project ON publication_versions(tenant_id,project_id,created_at DESC);

CREATE TABLE publication_resources (
  tenant_id text NOT NULL,
  version_id text NOT NULL,
  resource_id text NOT NULL,
  PRIMARY KEY (version_id,resource_id),
  FOREIGN KEY (version_id) REFERENCES publication_versions(id) ON DELETE CASCADE,
  FOREIGN KEY (resource_id) REFERENCES resources(id) ON DELETE RESTRICT
);
CREATE INDEX publication_resources_resource ON publication_resources(resource_id);

CREATE TABLE publication_current (
  tenant_id text NOT NULL,
  project_id text PRIMARY KEY,
  version_id text,
  pointer_revision bigint NOT NULL DEFAULT 0,
  updated_by text,
  updated_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id,project_id) REFERENCES projects(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY (version_id) REFERENCES publication_versions(id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id,updated_by) REFERENCES users(tenant_id,id)
);
