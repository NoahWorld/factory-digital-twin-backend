#!/usr/bin/env python3
"""Offline, bounded IFC/DXF -> metre, Y-up GLB conversion.

The CLI is a child process of the Java worker. It never fetches URLs, invokes
shell commands, repairs CAD topology, or silently discards modeling entities.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
import traceback
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

MAX_INPUT = 100 * 1024 * 1024
MAX_OUTPUT = 25 * 1024 * 1024
MAX_ENTITIES = 100_000
MAX_TRIANGLES = 2_000_000
MAX_PATH_POINTS = 100_000
UNITS = {"mm": .001, "cm": .01, "m": 1., "in": .0254, "ft": .3048}
INSUNITS = {1: "in", 2: "ft", 4: "mm", 5: "cm", 6: "m"}
ROLES = {"floor", "wall", "column", "equipment", "ignore"}
COLORS = {"floor": [166, 177, 186, 255], "wall": [216, 222, 228, 255],
          "column": [147, 158, 171, 255], "equipment": [91, 157, 202, 255]}
PROGRESS_STREAM = sys.stdout


class ConversionError(Exception):
    def __init__(self, code, message, *, issue=None, details=None):
        super().__init__(message)
        self.code = code
        self.details = details or ({"issues": [issue]} if issue else {})


def progress(stage, message, value):
    print(json.dumps({"stage": stage, "message": message, "progress": value}, ensure_ascii=False), file=PROGRESS_STREAM, flush=True)


def fail(code, message, entity=None):
    issue = None
    if entity:
        issue = {"code": code, "layer": entity.layer, "handle": entity.handle,
                 "entityType": entity.entity.dxftype(), "message": message}
    raise ConversionError(code, message, issue=issue)


def finite_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def read_options(path):
    if path is None:
        return {}
    if path.stat().st_size > 2 * 1024 * 1024:
        fail("engineering_invalid_options", "Options exceed 2 MiB")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        fail("engineering_invalid_options", "Expected options object")
    return value


def detect_format(path):
    if path.stat().st_size > MAX_INPUT:
        fail("engineering_geometry_limit", "Input exceeds 100 MiB")
    suffix = path.suffix.lower().lstrip(".")
    if suffix not in {"ifc", "dxf"}:
        fail("engineering_unsupported_format", "Supported source formats: IFC and DXF")
    if path.stat().st_size == 0:
        fail("engineering_invalid_file", "Source file is empty")
    return suffix


@dataclass
class CadEntity:
    entity: object
    matrix: object
    layer: str
    handle: str


def read_dxf(path):
    import ezdxf
    try:
        doc = ezdxf.readfile(path)
    except (ezdxf.DXFError, UnicodeError, ValueError, OSError) as error:
        raise ConversionError("engineering_invalid_file", f"DXF parse failed: {error}") from error
    return doc


def flatten_cad(doc):
    """Expand INSERT ourselves to retain source handles and layer-0 inheritance.

    Coordinates are transformed *after* flattening curves so non-uniform block
    scales do not lose circles, bulges or elliptical arcs.
    """
    from ezdxf.math import Matrix44
    result = []
    visited_count = 0

    def walk(entities, matrix, inherited_layer="0", prefix="", stack=()):
        nonlocal visited_count
        for index, entity in enumerate(entities):
            visited_count += 1
            if visited_count > MAX_ENTITIES:
                fail("engineering_geometry_limit", "Expanded CAD entities exceed 100000")
            handle = prefix + str(entity.dxf.get("handle", str(index)))
            layer = entity.dxf.get("layer", "0")
            if layer == "0":
                layer = inherited_layer
            source = CadEntity(entity, matrix, layer, handle)
            if entity.dxftype() != "INSERT":
                result.append(source)
                continue
            name = entity.dxf.name
            if name in stack or len(stack) >= 16:
                fail("engineering_invalid_geometry", f"Cyclic or excessively nested block: {name}", source)
            block = entity.block()
            if block is None or block.block.dxf.flags & 12:
                fail("engineering_invalid_geometry", f"Missing or external block: {name}; bind external references first", source)
            if entity.mcount + visited_count > MAX_ENTITIES:
                fail("engineering_geometry_limit", "CAD array exceeds entity budget", source)
            inserts = entity.multi_insert() if entity.mcount > 1 else [entity]
            for instance_index, insert in enumerate(inserts):
                walk(block, insert.matrix44() @ matrix, layer,
                     f"{handle}[{instance_index}]/", (*stack, name))
                # Attached attributes are real annotation entities, already in
                # the containing layout coordinates. Expose their layers so
                # users explicitly ignore them rather than silently losing them.
                walk(insert.attribs, matrix, layer, f"{handle}[{instance_index}]/ATTR/", (*stack, name))

    walk(doc.modelspace(), Matrix44())
    return result


def cad_inspection(doc, entities):
    grouped = defaultdict(Counter)
    for item in entities:
        grouped[item.layer][item.entity.dxftype()] += 1
    if len(grouped) > 2000:
        fail("engineering_geometry_limit", "CAD exceeds 2000 populated layers")
    raw_unit = int(doc.header.get("$INSUNITS", 0))
    units = INSUNITS.get(raw_unit)
    warnings = []
    if any(len(layout) for layout in doc.layouts if layout.name != "Model"):
        warnings.append("cad_paperspace_excluded")
    if raw_unit not in (0, *INSUNITS):
        warnings.append("cad_units_require_selection")
    return {"schemaVersion": 1, "format": "dxf", "units": units,
            "layers": [{"name": name, "entityCount": sum(types.values()), "types": sorted(types)}
                       for name, types in sorted(grouped.items())],
            "warnings": warnings, "objectCount": len(entities)}


def validate_cad_options(options, inspection):
    if options.get("schemaVersion") != 1 or options.get("units") not in UNITS:
        fail("engineering_invalid_options", "CAD requires schemaVersion 1 and explicit mm/cm/m/in/ft units")
    if not isinstance(options.get("center"), bool):
        fail("engineering_invalid_options", "center must be an explicit boolean")
    if inspection["units"] and inspection["units"] != options["units"]:
        fail("engineering_unit_mismatch", "Selected units disagree with DXF $INSUNITS",
             )
    layers = options.get("layers")
    if not isinstance(layers, list) or len(layers) > 2000:
        fail("engineering_invalid_options", "Expected at most 2000 explicit layer mappings")
    mappings = {}
    for layer in layers:
        if not isinstance(layer, dict) or not isinstance(layer.get("name"), str) or layer.get("role") not in ROLES:
            fail("engineering_invalid_options", "Layer mapping requires name and valid role")
        name = layer["name"]
        if name in mappings:
            fail("engineering_invalid_options", f"Duplicate layer mapping: {name}")
        if layer["role"] != "ignore":
            height, base = layer.get("height"), layer.get("base")
            if not finite_number(height) or not 0 < height <= 1000:
                fail("engineering_invalid_options", f"Layer {name}: height must be > 0 and <= 1000 metres")
            if not finite_number(base) or not -10000 <= base <= 10000:
                fail("engineering_invalid_options", f"Layer {name}: base must be -10000..10000 metres")
        mappings[name] = layer
    actual_layers = {layer["name"] for layer in inspection["layers"]}
    if set(mappings) != actual_layers:
        raise ConversionError("engineering_invalid_options", "Every populated layer must have exactly one mapping",
                              details={"missingLayers": sorted(actual_layers - set(mappings)),
                                       "unknownLayers": sorted(set(mappings) - actual_layers)})
    return mappings


def transform_magnification(source, scale):
    # The Frobenius norm bounds even nested non-uniform scales with rotation
    # (which can compose to shear); max(column length) is not an upper bound.
    axes = [source.matrix.transform_direction(axis).magnitude for axis in ((1, 0, 0), (0, 1, 0), (0, 0, 1))]
    magnification = math.sqrt(sum(axis * axis for axis in axes)) * scale
    if not math.isfinite(magnification) or magnification <= 0:
        fail("engineering_invalid_geometry", "Invalid block scale", source)
    return magnification


def path_points(path, source, scale):
    # Max 1 mm curve deviation in output; min 16 segments per curve.
    # Account for parent scale before flattening to preserve tolerance.
    magnification = transform_magnification(source, scale)
    points = []
    for point in path.flattening(distance=.001 / magnification, segments=16):
        point = source.matrix.transform(point)
        points.append((point.x * scale, point.y * scale, point.z * scale))
        if len(points) > MAX_PATH_POINTS:
            fail("engineering_geometry_limit", "Curve exceeds 100000 vertices", source)
    if not points or not all(math.isfinite(value) for point in points for value in point):
        fail("engineering_invalid_geometry", "Empty or non-finite contour", source)
    if max(abs(point[2]) for point in points) > .00001:
        fail("engineering_invalid_geometry", "2D outlines must lie on the XY plane at Z = 0", source)
    return [(point[0], point[1]) for point in points]


def checked_polygon(points, source):
    from shapely.geometry import Polygon
    from shapely.validation import explain_validity
    if len(points) < 4 or math.dist(points[0], points[-1]) > .00001:
        fail("engineering_invalid_geometry", "Modeling contour is not closed", source)
    polygon = Polygon(points)
    if not polygon.is_valid or polygon.area <= 1e-10:
        fail("engineering_invalid_geometry", f"Invalid modeling contour: {explain_validity(polygon)}", source)
    return polygon


def hatch_polygons(entity, source, scale):
    from ezdxf.path import from_hatch_boundary_path
    from ezdxf.entities.boundary_paths import EdgePath
    if len(entity.paths) > 1000:
        fail("engineering_geometry_limit", "Hatch exceeds 1000 boundary rings", source)
    style = entity.dxf.get("hatch_style", 0)
    if style not in (0, 1, 2):
        fail("engineering_invalid_geometry", "Unknown hatch island style", source)
    rings = []
    for boundary in entity.paths.rendering_paths(style):
        if isinstance(boundary, EdgePath):
            # ezdxf's path converter auto-closes edge gaps. Verify connectivity
            # before invoking it, so malformed input never gains invented walls.
            loop_start = loop_end = None
            tolerance = .00001 / transform_magnification(source, scale)
            close = lambda a, b: math.dist(a, b) <= tolerance
            for edge in boundary.edges:
                start, end = edge.real_start_point, edge.real_end_point
                if loop_start is None:
                    loop_start, loop_end = start, end
                elif close(loop_end, start):
                    loop_end = end
                elif close(loop_end, end):
                    loop_end = start
                elif close(loop_start, end):
                    loop_start = start
                elif close(loop_start, start):
                    loop_start = end
                elif close(loop_start, loop_end):
                    loop_start, loop_end = start, end
                else:
                    fail("engineering_invalid_geometry", "Hatch edges contain an open gap", source)
            if loop_start is None or not close(loop_start, loop_end):
                fail("engineering_invalid_geometry", "Hatch edge boundary is not closed", source)
        combined = from_hatch_boundary_path(boundary, entity.ocs(), entity.dxf.elevation.z)
        paths = combined.sub_paths() if combined.has_sub_paths else [combined]
        rings.extend(checked_polygon(path_points(path, source, scale), source) for path in paths)
    if not rings:
        fail("engineering_invalid_geometry", "Hatch has no boundary", source)
    # DXF hatch style filters boundary flags above. Even/odd island
    # nesting then handles holes and disconnected islands deterministically.
    for i, left in enumerate(rings):
        for right in rings[i + 1:]:
            if left.boundary.intersects(right.boundary):
                fail("engineering_invalid_geometry", "Hatch boundaries intersect or touch", source)
    output = []
    from shapely.geometry import Polygon
    parents = [{j for j, other in enumerate(rings) if j != i and other.contains(ring)}
               for i, ring in enumerate(rings)]
    for index, ring in enumerate(rings):
        depth = len(parents[index])
        if depth % 2:
            continue
        children = [child for j, child in enumerate(rings) if index in parents[j] and len(parents[j]) == depth + 1]
        output.append(Polygon(ring.exterior.coords, [child.exterior.coords for child in children]))
    return output


def cad_meshes(source, mapping, scale):
    import numpy as np
    import trimesh
    from ezdxf.path import make_path
    entity = source.entity
    kind = entity.dxftype()
    polygons = None
    meshes = []
    if kind in {"LWPOLYLINE", "POLYLINE", "CIRCLE", "ELLIPSE", "SPLINE"} and not (
            kind == "POLYLINE" and (entity.is_poly_face_mesh or entity.is_polygon_mesh)):
        if kind == "LWPOLYLINE" and (entity.has_width):
            fail("engineering_unsupported_entity", "Polyline widths are strokes; supply the occupied closed boundary", source)
        if kind == "POLYLINE" and (entity.dxf.get("default_start_width", 0) or entity.dxf.get("default_end_width", 0)
                                    or any(v.dxf.get("start_width", 0) or v.dxf.get("end_width", 0) for v in entity.vertices)):
            fail("engineering_unsupported_entity", "Polyline widths are strokes; supply the occupied closed boundary", source)
        path = make_path(entity)
        if not path.is_closed:
            fail("engineering_invalid_geometry", "Modeling contour is open; close its boundary or ignore its layer", source)
        polygons = [checked_polygon(path_points(path, source, scale), source)]
    elif kind in {"HATCH", "MPOLYGON"}:
        polygons = hatch_polygons(entity, source, scale)
    elif kind in {"SOLID", "TRACE"}:
        points = [source.matrix.transform(point) for point in entity.wcs_vertices(close=True)]
        if any(abs(point.z * scale) > .00001 for point in points):
            fail("engineering_invalid_geometry", "2D solids must lie on XY at Z = 0", source)
        polygons = [checked_polygon([(point.x * scale, point.y * scale) for point in points], source)]
    elif kind in {"3DFACE", "MESH"} or (kind == "POLYLINE" and (entity.is_poly_face_mesh or entity.is_polygon_mesh)):
        from ezdxf.render import MeshBuilder
        if kind == "MESH":
            if entity.dxf.subdivision_levels != 0:
                fail("engineering_unsupported_entity", "Subdivision mesh must be converted to its evaluated surface mesh in CAD first", source)
            builder = MeshBuilder.from_mesh(entity)
        elif kind == "POLYLINE" and entity.is_polygon_mesh:
            if entity.dxf.smooth_type or entity.dxf.flags & 6:
                fail("engineering_unsupported_entity", "Fitted polygon mesh must be converted to its evaluated surface mesh in CAD first", source)
            builder = MeshBuilder()
            m_count, n_count = entity.dxf.m_count, entity.dxf.n_count
            if m_count < 2 or n_count < 2 or len(entity.vertices) != m_count * n_count:
                fail("engineering_invalid_geometry", "Polygon mesh vertex grid is incomplete", source)
            vertices = entity.get_mesh_vertex_cache()
            # ezdxf MeshBuilder.from_polyface omits closed mesh seam faces.
            # Explicit grid wrapping retains both closed dimensions.
            for m in range(m_count if entity.is_m_closed else m_count - 1):
                for n in range(n_count if entity.is_n_closed else n_count - 1):
                    m_next, n_next = (m + 1) % m_count, (n + 1) % n_count
                    builder.add_face((vertices[m, n], vertices[m, n_next],
                                      vertices[m_next, n_next], vertices[m_next, n]))
        elif kind == "POLYLINE":
            builder = MeshBuilder.from_polyface(entity)
        else:
            builder = MeshBuilder()
            builder.add_face(entity.wcs_vertices(close=False))
        vertices = []
        faces = []
        # MeshBuilder.tessellation triangulates non-convex faces correctly.
        for face in builder.tessellation(max_vertex_count=3):
            start = len(vertices)
            transformed = [tuple(source.matrix.transform(v)) for v in face]
            if len(transformed) != 3:
                fail("engineering_invalid_geometry", "Mesh triangulation produced a non-triangle", source)
            vertices.extend(transformed)
            faces.append([start, start + 1, start + 2])
            if len(faces) > MAX_TRIANGLES:
                fail("engineering_geometry_limit", "Mesh exceeds 2000000 triangles", source)
        if not faces:
            fail("engineering_invalid_geometry", "Mesh has no triangular faces", source)
        mesh = trimesh.Trimesh(vertices=np.array(vertices) * scale, faces=faces, process=False)
        if source.matrix.determinant() < 0:
            mesh.invert()
        meshes = [mesh]
    else:
        fail("engineering_unsupported_entity", f"Unsupported modeling entity {kind}; convert to supported closed outlines or explicitly ignore its layer", source)
    if polygons is not None:
        for polygon in polygons:
            mesh = trimesh.creation.extrude_polygon(polygon, height=mapping["height"], engine="earcut")
            mesh.apply_translation([0, 0, mapping["base"]])
            meshes.append(mesh)
    for mesh in meshes:
        mesh.visual.vertex_colors = COLORS[mapping["role"]]
        mesh.metadata = {"sourceFormat": "dxf", "sourceHandle": source.handle,
                         "sourceLayer": source.layer, "entityType": kind,
                         "role": mapping["role"], "heightMetres": mapping["height"], "baseMetres": mapping["base"]}
    return meshes


def convert_dxf(doc, entities, options):
    inspection = cad_inspection(doc, entities)
    mappings = validate_cad_options(options, inspection)
    scale = UNITS[options["units"]]
    output = []
    warnings = list(inspection["warnings"])
    if any(layer["role"] == "ignore" for layer in mappings.values()):
        warnings.append("cad_ignored_layers")
    if any(layer["role"] == "equipment" for layer in mappings.values()):
        warnings.append("cad_simplified_equipment")
    total_triangles = 0
    modeled_count = 0
    for index, source in enumerate(entities):
        mapping = mappings[source.layer]
        if mapping["role"] == "ignore":
            continue
        try:
            meshes = cad_meshes(source, mapping, scale)
        except ConversionError:
            raise
        except (ValueError, TypeError, RuntimeError, AttributeError, ArithmeticError) as error:
            fail("engineering_invalid_geometry", f"CAD geometry conversion failed: {error}", source)
        for part, mesh in enumerate(meshes):
            total_triangles += len(mesh.faces)
            if total_triangles > MAX_TRIANGLES:
                fail("engineering_geometry_limit", "Output exceeds 2000000 triangles", source)
            output.append((f"DXF_{source.handle}_{part}", mesh))
        modeled_count += 1
        if index % 25 == 0:
            progress("geometry", f"Converted CAD entity {index + 1}/{len(entities)}", 15 + int(65 * (index + 1) / len(entities)))
    return output, warnings, modeled_count


def read_ifc(path):
    import ifcopenshell
    try:
        doc = ifcopenshell.open(str(path))
    except (RuntimeError, ValueError, OSError) as error:
        raise ConversionError("engineering_invalid_file", f"IFC parse failed: {error}") from error
    return doc


def ifc_products(doc):
    products = doc.by_type("IfcProduct")
    if len(products) > MAX_ENTITIES:
        fail("engineering_geometry_limit", "IFC exceeds 100000 products")
    represented = []
    missing = 0
    openings = 0
    for product in products:
        if product.is_a("IfcOpeningElement"):
            openings += 1
        elif getattr(product, "Representation", None):
            represented.append(product)
        else:
            missing += 1
    warnings = []
    if missing:
        warnings.append("ifc_products_without_geometry_excluded")
    if openings:
        warnings.append("ifc_openings_applied")
    return represented, warnings


def ifc_inspection(doc):
    import ifcopenshell.util.unit
    length = ifcopenshell.util.unit.get_project_unit(doc, "LENGTHUNIT")
    if length is None:
        fail("engineering_invalid_file", "IFC project has no declared length unit")
    scale = ifcopenshell.util.unit.calculate_unit_scale(doc)
    units = next((name for name, factor in UNITS.items() if abs(scale - factor) < 1e-12), "native")
    products, warnings = ifc_products(doc)
    return {"schemaVersion": 1, "format": "ifc", "units": units, "layers": [],
            "warnings": warnings, "objectCount": len(products)}


def convert_ifc(doc):
    import numpy as np
    import trimesh
    import ifcopenshell.geom
    ifc_inspection(doc)
    products, warnings = ifc_products(doc)
    settings = ifcopenshell.geom.settings()
    settings.set("use-world-coords", True)
    settings.set("convert-back-units", False)
    settings.set("weld-vertices", True)
    meshes = []
    triangles = 0
    for index, product in enumerate(products):
        context = {"code": "engineering_invalid_geometry", "handle": str(product.id()),
                   "entityType": product.is_a(), "guid": product.GlobalId}
        try:
            shape = ifcopenshell.geom.create_shape(settings, product)
            if shape is None:
                raise ValueError("IfcOpenShell returned no geometry")
            geometry = shape.geometry
            vertices = np.array(geometry.verts, dtype=np.float64).reshape((-1, 3))
            faces = np.array(geometry.faces, dtype=np.int64).reshape((-1, 3))
            if not len(faces) or not len(vertices):
                raise ValueError("Represented product contains no surface mesh")
            mesh = trimesh.Trimesh(vertices=vertices, faces=faces, process=False)
            colors = []
            for material in geometry.materials:
                color = material.diffuse
                transparency = material.transparency
                alpha = 1 - transparency if math.isfinite(transparency) else 1
                colors.append([round(max(0, min(1, v)) * 255) for v in (color.r(), color.g(), color.b(), alpha)])
            material_ids = geometry.material_ids
            if len(material_ids) != len(faces):
                raise ValueError("Face/material count mismatch")
            default_color = [200, 207, 215, 255]
            mesh.unmerge_vertices()
            face_colors = np.array([colors[i] if 0 <= i < len(colors) else default_color for i in material_ids], dtype=np.uint8)
            mesh.visual.vertex_colors = np.repeat(face_colors, 3, axis=0)
            mesh.metadata = {"sourceFormat": "ifc", "ifcGuid": product.GlobalId,
                             "ifcType": product.is_a(), "ifcName": product.Name or "", "ifcStepId": product.id()}
        except Exception as error:
            context["message"] = str(error)
            raise ConversionError("engineering_invalid_geometry", f"IFC product #{product.id()} ({product.GlobalId}) failed: {error}", issue=context) from error
        triangles += len(faces)
        if triangles > MAX_TRIANGLES:
            fail("engineering_geometry_limit", "Output exceeds 2000000 triangles")
        meshes.append((f"IFC_{product.GlobalId}_{product.id()}", mesh))
        progress("geometry", f"Converted IFC product {index + 1}/{len(products)}", 15 + int(65 * (index + 1) / len(products)))
    return meshes, warnings, len(meshes)


def export_model(meshes, output, options, source_format, warnings, object_count):
    import numpy as np
    import trimesh
    if not meshes:
        fail("engineering_empty_model", "No model geometry remains after explicit layer selection")
    bounds = np.array([mesh.bounds for _, mesh in meshes])
    if not np.isfinite(bounds).all():
        fail("engineering_invalid_geometry", "Model contains non-finite coordinates")
    minimum, maximum = bounds[:, 0, :].min(axis=0), bounds[:, 1, :].max(axis=0)
    origin = np.array([(minimum[0] + maximum[0]) / 2, (minimum[1] + maximum[1]) / 2, minimum[2]]) if options.get("center", True) else np.zeros(3)
    if np.max(maximum - minimum) > 1_000_000:
        fail("engineering_geometry_limit", "Model span exceeds 1000 km; check units")
    rotation = np.array([[1, 0, 0, 0], [0, 0, 1, 0], [0, -1, 0, 0], [0, 0, 0, 1]], dtype=float)
    scene = trimesh.Scene()
    scene.metadata = {"schemaVersion": 1, "sourceFormat": source_format, "sourceOrigin": origin.tolist(),
                      "sourceAxes": "Z-up", "outputAxes": "Y-up", "units": "m"}
    triangle_count = 0
    for name, mesh in meshes:
        if not np.isfinite(mesh.vertices).all() or np.any(mesh.faces < 0) or np.any(mesh.faces >= len(mesh.vertices)):
            fail("engineering_invalid_geometry", f"Invalid mesh vertices or face indices: {name}")
        triangle_count += len(mesh.faces)
        mesh.apply_translation(-origin)
        mesh.apply_transform(rotation)
        # GLB position accessors are float32. Detect lost coordinates and
        # collapsed faces before serialization instead of exporting a model
        # whose source double-precision bounds merely look correct.
        quantized = mesh.vertices.astype(np.float32).astype(np.float64)
        triangles = mesh.vertices[mesh.faces]
        quantized_triangles = quantized[mesh.faces]
        original_area = np.linalg.norm(np.cross(triangles[:, 1] - triangles[:, 0], triangles[:, 2] - triangles[:, 0]), axis=1)
        quantized_area = np.linalg.norm(np.cross(quantized_triangles[:, 1] - quantized_triangles[:, 0], quantized_triangles[:, 2] - quantized_triangles[:, 0]), axis=1)
        if np.any(original_area <= 0):
            fail("engineering_invalid_geometry", f"Mesh contains degenerate triangles: {name}")
        if np.max(np.abs(quantized - mesh.vertices)) > .001 or np.any(quantized_area <= 0):
            metadata = mesh.metadata
            issue = {"code": "engineering_precision_loss", "layer": metadata.get("sourceLayer", ""),
                     "handle": metadata.get("sourceHandle", str(metadata.get("ifcStepId", ""))),
                     "entityType": metadata.get("entityType", metadata.get("ifcType", ""))}
            raise ConversionError("engineering_precision_loss", "GLB float32 loses geometry precision; enable coordinate centering or split a large model", issue=issue)
        scene.add_geometry(mesh, node_name=name, geom_name=name, metadata=mesh.metadata)
    progress("export", "Writing metre Y-up GLB", 90)
    data = scene.export(file_type="glb")
    if len(data) > MAX_OUTPUT:
        fail("engineering_geometry_limit", "Generated GLB exceeds 25 MiB; reduce or split the source model")
    output.joinpath("model.glb").write_bytes(data)
    return {"schemaVersion": 1, "format": source_format, "objectCount": object_count,
            "triangleCount": triangle_count, "warnings": warnings,
            "bounds": {"min": scene.bounds[0].tolist(), "max": scene.bounds[1].tolist()},
            "sourceOrigin": origin.tolist()}


def run(mode, input_path, output_path, options):
    output_path.mkdir(parents=True, exist_ok=True)
    # A reused output directory must never expose a previous successful model.
    output_path.joinpath("model.glb").unlink(missing_ok=True)
    progress("reading", "Reading source file", 5)
    source_format = detect_format(input_path)
    if source_format == "dxf":
        doc = read_dxf(input_path)
        entities = flatten_cad(doc)
        if mode == "inspect":
            return cad_inspection(doc, entities)
        progress("validating", "Validating explicit CAD layer mappings", 10)
        meshes, warnings, count = convert_dxf(doc, entities, options)
    else:
        doc = read_ifc(input_path)
        if mode == "inspect":
            return ifc_inspection(doc)
        if options.get("schemaVersion") != 1 or not isinstance(options.get("center"), bool):
            fail("engineering_invalid_options", "IFC requires schemaVersion 1 and explicit center boolean")
        progress("geometry", "Converting native IFC product geometry", 10)
        meshes, warnings, count = convert_ifc(doc)
    return export_model(meshes, output_path, options, source_format, warnings, count)


def main():
    global PROGRESS_STREAM
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("inspect", "convert"), required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--options", type=Path)
    args = parser.parse_args()
    # Retain one dedicated pipe for the JSONL protocol, route Python/C/C++
    # library stdout to diagnostics so native messages cannot corrupt it.
    sys.stdout.flush()
    PROGRESS_STREAM = os.fdopen(os.dup(sys.stdout.fileno()), "w", encoding="utf-8", buffering=1)
    os.dup2(sys.stderr.fileno(), sys.stdout.fileno())
    args.output.mkdir(parents=True, exist_ok=True)
    try:
        result = run(args.mode, args.input, args.output, read_options(args.options))
        args.output.joinpath("result.json").write_text(json.dumps(result, ensure_ascii=False, allow_nan=False), encoding="utf-8")
        progress("complete", "Inspection complete" if args.mode == "inspect" else "GLB conversion complete", 100)
        return 0
    except ConversionError as error:
        result = {"error": {"code": error.code, "message": str(error), "details": error.details}}
        print(f"{error.code}: {error}", file=sys.stderr, flush=True)
    except Exception as error:
        # The process boundary records unexpected failures, never substitutes a
        # model, and returns nonzero. Tracebacks remain in worker diagnostics.
        traceback.print_exc(file=sys.stderr)
        result = {"error": {"code": "engineering_conversion_failed", "message": str(error)}}
    args.output.joinpath("model.glb").unlink(missing_ok=True)
    args.output.joinpath("result.json").write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
    return 1


if __name__ == "__main__":
    sys.exit(main())
