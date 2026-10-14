CREATE TABLE site_captures (
 id text PRIMARY KEY, tenant_id text NOT NULL, project_id text NOT NULL,
 name text NOT NULL, input_kind text NOT NULL CHECK(input_kind IN ('images','video')),
 client_request_id text NOT NULL, request_json jsonb NOT NULL,
 status text NOT NULL CHECK(status IN ('uploading','queued','running','succeeded','failed','canceled')),
 stage text NOT NULL, completed integer NOT NULL DEFAULT 0, total integer NOT NULL DEFAULT 0,
 attempts integer NOT NULL DEFAULT 0, lease_owner text, lease_until timestamptz,
 result jsonb, output_key text, error_code text, error_message text,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,project_id,client_request_id),
 FOREIGN KEY(tenant_id,project_id) REFERENCES projects(tenant_id,id) ON DELETE CASCADE
);
CREATE TABLE site_capture_sources (
 capture_id text NOT NULL REFERENCES site_captures(id) ON DELETE CASCADE,
 source_index integer NOT NULL CHECK(source_index BETWEEN 0 AND 119),
 filename text NOT NULL, byte_size bigint NOT NULL CHECK(byte_size>0), content_type text NOT NULL,
 uploaded boolean NOT NULL DEFAULT false, object_key text, sha256 text,
 upload_owner text, upload_until timestamptz,
 PRIMARY KEY(capture_id,source_index)
);
CREATE TABLE site_capture_objects (
 capture_id text NOT NULL REFERENCES site_captures(id) ON DELETE CASCADE,
 object_key text PRIMARY KEY
);
CREATE TABLE site_capture_workers (
 id text PRIMARY KEY, available boolean NOT NULL, reason text,
 heartbeat_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX site_capture_schedule ON site_captures(status,lease_until,created_at);
CREATE INDEX site_capture_project ON site_captures(tenant_id,project_id,created_at DESC);
