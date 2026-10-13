CREATE TABLE engineering_imports (
 id text PRIMARY KEY, tenant_id text NOT NULL, project_id text NOT NULL,
 name text NOT NULL, filename text NOT NULL, format text NOT NULL CHECK(format IN ('ifc','dxf')),
 source_key text NOT NULL UNIQUE, source_bytes bigint NOT NULL CHECK(source_bytes>0),
 status text NOT NULL CHECK(status IN ('queued','inspecting','needs_configuration','converting','completed','failed')),
 mode text NOT NULL DEFAULT 'inspect' CHECK(mode IN ('inspect','convert')),
 stage text NOT NULL, message text NOT NULL, progress int NOT NULL DEFAULT 0 CHECK(progress BETWEEN 0 AND 100),
 inspection jsonb, options jsonb, result jsonb, error jsonb,
 resource_id text REFERENCES resources(id) ON DELETE SET NULL,
 attempts int NOT NULL DEFAULT 0, lease_owner text, lease_until timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,project_id) REFERENCES projects(tenant_id,id) ON DELETE CASCADE
);
CREATE INDEX engineering_import_schedule ON engineering_imports(status,lease_until,created_at);
CREATE INDEX engineering_import_project ON engineering_imports(tenant_id,project_id,created_at DESC);
