CREATE TABLE tenants (id text PRIMARY KEY, name text NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE users (
 id text PRIMARY KEY, tenant_id text NOT NULL REFERENCES tenants(id), email text NOT NULL,
 login_name text NOT NULL, display_name text NOT NULL, password_hash text NOT NULL,
 role text NOT NULL CHECK(role IN ('platform_admin','delivery_manager','viewer')),
 active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,email), UNIQUE(tenant_id,login_name)
);
CREATE TABLE sessions (
 token_hash text PRIMARY KEY, tenant_id text NOT NULL, user_id text NOT NULL,
 expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,user_id) REFERENCES users(tenant_id,id) ON DELETE CASCADE
);
CREATE INDEX sessions_expiry ON sessions(expires_at);
CREATE TABLE projects (
 id text PRIMARY KEY, tenant_id text NOT NULL REFERENCES tenants(id), name text NOT NULL,
 project_type text NOT NULL CHECK(project_type IN ('2d','3d')), status text NOT NULL DEFAULT 'draft',
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(tenant_id,id)
);
CREATE INDEX projects_tenant ON projects(tenant_id,updated_at DESC);
CREATE TABLE project_members (
 tenant_id text NOT NULL, project_id text NOT NULL, user_id text NOT NULL,
 role text NOT NULL CHECK(role IN ('owner','editor','viewer')), PRIMARY KEY(project_id,user_id),
 FOREIGN KEY(tenant_id,project_id) REFERENCES projects(tenant_id,id) ON DELETE CASCADE,
 FOREIGN KEY(tenant_id,user_id) REFERENCES users(tenant_id,id) ON DELETE CASCADE
);
CREATE TABLE documents (
 tenant_id text NOT NULL, project_id text PRIMARY KEY, kind text NOT NULL CHECK(kind IN ('canvas','scene')),
 revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0), settings jsonb NOT NULL,
 linked_project_id text, updated_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,project_id) REFERENCES projects(tenant_id,id) ON DELETE CASCADE,
 FOREIGN KEY(tenant_id,linked_project_id) REFERENCES projects(tenant_id,id) ON DELETE RESTRICT,
 UNIQUE(tenant_id,project_id)
);
CREATE TABLE document_items (
 tenant_id text NOT NULL, project_id text NOT NULL, id text NOT NULL, sort_order int NOT NULL,
 body jsonb NOT NULL, PRIMARY KEY(project_id,id),
 FOREIGN KEY(tenant_id,project_id) REFERENCES documents(tenant_id,project_id) ON DELETE CASCADE
);
CREATE TABLE assets (
 id text PRIMARY KEY, tenant_id text NOT NULL, project_id text NOT NULL, asset_key text NOT NULL,
 body jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,project_id) REFERENCES projects(tenant_id,id) ON DELETE CASCADE,
 UNIQUE(tenant_id,project_id,id), UNIQUE(project_id,asset_key)
);
CREATE TABLE data_sources (
 id text PRIMARY KEY, tenant_id text NOT NULL, project_id text NOT NULL, body jsonb NOT NULL,
 next_poll_at timestamptz NOT NULL DEFAULT now(), lease_owner text, lease_until timestamptz,
 generation bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,project_id) REFERENCES projects(tenant_id,id) ON DELETE CASCADE,
 UNIQUE(tenant_id,project_id,id)
);
CREATE INDEX source_schedule ON data_sources(next_poll_at,lease_until);
CREATE TABLE data_bindings (
 id text PRIMARY KEY, tenant_id text NOT NULL, project_id text NOT NULL, asset_id text NOT NULL,
 source_id text NOT NULL, metric_key text NOT NULL, body jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,project_id,asset_id) REFERENCES assets(tenant_id,project_id,id) ON DELETE CASCADE,
 FOREIGN KEY(tenant_id,project_id,source_id) REFERENCES data_sources(tenant_id,project_id,id) ON DELETE RESTRICT,
 UNIQUE(asset_id,metric_key)
);
CREATE TABLE resources (
 id text PRIMARY KEY, tenant_id text NOT NULL, project_id text NOT NULL,
 kind text NOT NULL CHECK(kind IN ('model','image','media')), object_key text NOT NULL UNIQUE,
 filename text NOT NULL, content_type text NOT NULL, byte_size bigint NOT NULL,
 sha256 text, state text NOT NULL CHECK(state IN ('uploading','processing','ready','failed')),
 inspection jsonb, error text, upload_id text, expires_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,project_id) REFERENCES projects(tenant_id,id) ON DELETE RESTRICT,
 UNIQUE(tenant_id,project_id,id)
);
CREATE INDEX resources_project ON resources(tenant_id,project_id,state);
CREATE TABLE jobs (
 id text PRIMARY KEY, tenant_id text NOT NULL, project_id text NOT NULL, resource_id text NOT NULL,
 kind text NOT NULL CHECK(kind='inspect_resource'), state text NOT NULL DEFAULT 'queued' CHECK(state IN ('queued','running','succeeded','failed')),
 attempts int NOT NULL DEFAULT 0, lease_owner text, lease_until timestamptz, error text,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,project_id,resource_id) REFERENCES resources(tenant_id,project_id,id) ON DELETE CASCADE,
 UNIQUE(resource_id,kind)
);
CREATE INDEX job_schedule ON jobs(state,lease_until);
CREATE TABLE audit_events (
 id bigserial PRIMARY KEY, tenant_id text NOT NULL, user_id text, project_id text,
 action text NOT NULL, request_id text NOT NULL, details jsonb NOT NULL DEFAULT '{}', created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE login_attempts (key_hash text PRIMARY KEY, attempts int NOT NULL, resets_at timestamptz NOT NULL);
