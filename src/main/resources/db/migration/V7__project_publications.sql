CREATE TABLE project_publications (
  id text PRIMARY KEY,
  tenant_id text NOT NULL,
  project_id text NOT NULL,
  created_by text NOT NULL,
  document_revision bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, project_id),
  FOREIGN KEY (tenant_id, project_id) REFERENCES projects(tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE project_publication_scopes (
  tenant_id text NOT NULL,
  publication_id text NOT NULL,
  project_id text NOT NULL,
  document_revision bigint NOT NULL,
  PRIMARY KEY (publication_id, project_id),
  FOREIGN KEY (tenant_id, publication_id)
    REFERENCES project_publications(tenant_id, id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, project_id) REFERENCES projects(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX project_publication_scopes_project
  ON project_publication_scopes(tenant_id, project_id);
