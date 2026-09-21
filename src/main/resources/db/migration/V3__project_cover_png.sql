-- Browser screenshots replace generated SVG concepts. Existing covers become explicitly pending.
DROP TRIGGER projects_create_default_cover ON projects;
DROP FUNCTION create_default_project_cover();

ALTER TABLE project_covers
  DROP COLUMN svg,
  DROP COLUMN renderer_version,
  DROP COLUMN content_type,
  ALTER COLUMN source_revision DROP NOT NULL,
  ALTER COLUMN source_revision DROP DEFAULT,
  ADD COLUMN status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'ready')),
  ADD COLUMN png bytea CHECK (octet_length(png) BETWEEN 45 AND 2097152);

UPDATE project_covers SET source_revision=NULL, status='pending', updated_at=now();

ALTER TABLE project_covers
  ADD CONSTRAINT project_covers_png_dimensions CHECK (
    png IS NULL OR (
      substring(png FROM 1 FOR 8)=decode('89504e470d0a1a0a','hex')
      AND substring(png FROM 9 FOR 8)=decode('0000000d49484452','hex')
      AND substring(png FROM 17 FOR 8)=decode('000003c00000021c','hex')
    )
  ),
  ADD CONSTRAINT project_covers_png_source CHECK ((png IS NULL) = (source_revision IS NULL)),
  ADD CONSTRAINT project_covers_ready_png CHECK (status <> 'ready' OR png IS NOT NULL);

CREATE INDEX document_items_scene_cover_reference ON document_items
  (tenant_id, (body->'props'->>'sceneProjectId'), project_id)
  WHERE body->>'type'='scene-3d';

CREATE FUNCTION create_pending_project_cover() RETURNS trigger AS $$
BEGIN
  INSERT INTO project_covers(tenant_id, project_id) VALUES (NEW.tenant_id, NEW.id);
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER projects_create_pending_cover
AFTER INSERT ON projects
FOR EACH ROW EXECUTE FUNCTION create_pending_project_cover();
