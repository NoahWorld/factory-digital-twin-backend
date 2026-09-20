CREATE TABLE project_covers (
  tenant_id text NOT NULL,
  project_id text PRIMARY KEY,
  revision bigint NOT NULL DEFAULT 1 CHECK (revision >= 1),
  source_revision bigint NOT NULL DEFAULT 0 CHECK (source_revision >= 0),
  renderer_version integer NOT NULL DEFAULT 0 CHECK (renderer_version >= 0),
  content_type text NOT NULL DEFAULT 'image/svg+xml' CHECK (content_type = 'image/svg+xml'),
  svg text NOT NULL CHECK (octet_length(svg) <= 524288),
  updated_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id, project_id) REFERENCES projects(tenant_id, id) ON DELETE CASCADE,
  UNIQUE (tenant_id, project_id)
);

CREATE INDEX document_items_cover_order
  ON document_items(tenant_id, project_id, sort_order, id);

CREATE FUNCTION create_default_project_cover() RETURNS trigger AS $$
BEGIN
  INSERT INTO project_covers(tenant_id, project_id, svg)
  VALUES (
    NEW.tenant_id,
    NEW.id,
    '<svg xmlns="http://www.w3.org/2000/svg" width="480" height="270" viewBox="0 0 480 270" role="img" aria-label="项目默认封面"><rect width="480" height="270" fill="#071525"/><path d="M0 54H480M0 108H480M0 162H480M0 216H480M96 0V270M192 0V270M288 0V270M384 0V270" stroke="#276f8d" stroke-opacity=".22"/><path d="M240 82l54 31-54 31-54-31zM186 113v52l54 31v-52M294 113v52l-54 31" fill="none" stroke="#55d8ff" stroke-width="3"/><text x="240" y="230" fill="#9edff2" font-family="system-ui,sans-serif" font-size="14" text-anchor="middle">PROJECT COVER</text></svg>'
  );
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER projects_create_default_cover
AFTER INSERT ON projects
FOR EACH ROW EXECUTE FUNCTION create_default_project_cover();

INSERT INTO project_covers(tenant_id, project_id, source_revision, svg)
SELECT
  p.tenant_id,
  p.id,
  COALESCE(d.revision, 0),
  '<svg xmlns="http://www.w3.org/2000/svg" width="480" height="270" viewBox="0 0 480 270" role="img" aria-label="项目默认封面"><rect width="480" height="270" fill="#071525"/><path d="M0 54H480M0 108H480M0 162H480M0 216H480M96 0V270M192 0V270M288 0V270M384 0V270" stroke="#276f8d" stroke-opacity=".22"/><path d="M240 82l54 31-54 31-54-31zM186 113v52l54 31v-52M294 113v52l-54 31" fill="none" stroke="#55d8ff" stroke-width="3"/><text x="240" y="230" fill="#9edff2" font-family="system-ui,sans-serif" font-size="14" text-anchor="middle">PROJECT COVER</text></svg>'
FROM projects p
LEFT JOIN documents d ON d.tenant_id = p.tenant_id AND d.project_id = p.id
ON CONFLICT (project_id) DO NOTHING;
