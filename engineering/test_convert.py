import json
import math
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import ezdxf
import numpy as np
import trimesh

import convert
from make_fixtures import make_dxf, make_ifc, cad_options


class ConversionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.output = self.directory / "out"

    def run_model(self, path, options):
        result = convert.run("convert", path, self.output, options)
        scene = trimesh.load(self.output / "model.glb", force="scene", process=False)
        self.assertTrue(scene.geometry)
        self.assertTrue(np.isfinite(scene.bounds).all())
        return result, scene

    def cad(self, kind="m", height=2):
        doc = ezdxf.new("R2013")
        doc.units = {"m": 6, "mm": 4}[kind]
        options = {"schemaVersion": 1, "units": kind, "center": False,
                   "layers": [{"name": "0", "role": "wall", "height": height, "base": 0}]}
        return doc, options

    def save_cad(self, doc):
        path = self.directory / "input.dxf"
        doc.saveas(path)
        return path

    def test_layout_units_axes_metadata_and_nested_blocks(self):
        path = self.directory / "layout.dxf"
        make_dxf(path)
        result, scene = self.run_model(path, cad_options())
        np.testing.assert_allclose(scene.bounds, [[-10, 0, -6], [10, 4.2, 6]], atol=1e-6)
        self.assertEqual(result["objectCount"], 12)
        np.testing.assert_allclose(result["sourceOrigin"], [10, 6, -.2])
        self.assertIn("cad_simplified_equipment", result["warnings"])
        equipment = [m for m in scene.geometry.values() if m.metadata.get("role") == "equipment"]
        self.assertEqual(len(equipment), 2)
        self.assertTrue(all("/" in m.metadata["sourceHandle"] for m in equipment))
        np.testing.assert_allclose(sorted(m.extents[0] for m in equipment), [1, 2], atol=1e-5)

    def test_hatch_holes_preserve_volume(self):
        doc, options = self.cad()
        hatch = doc.modelspace().add_hatch()
        hatch.paths.add_polyline_path([(0, 0), (10, 0), (10, 10), (0, 10)], is_closed=True, flags=1)
        hatch.paths.add_polyline_path([(2, 2), (4, 2), (4, 4), (2, 4)], is_closed=True, flags=0)
        result, scene = self.run_model(self.save_cad(doc), options)
        self.assertEqual(result["objectCount"], 1)
        self.assertAlmostEqual(sum(mesh.volume for mesh in scene.geometry.values()), 192, places=4)

    def test_hatch_island_styles_and_open_edges(self):
        for style, volume in [(0, 176), (1, 168), (2, 200)]:
            with self.subTest(style=style):
                doc, options = self.cad()
                hatch = doc.modelspace().add_hatch(dxfattribs={"hatch_style": style})
                for points, flags in [([(0, 0), (10, 0), (10, 10), (0, 10)], 1),
                                      ([(2, 2), (6, 2), (6, 6), (2, 6)], 16),
                                      ([(3, 3), (5, 3), (5, 5), (3, 5)], 0)]:
                    hatch.paths.add_polyline_path(points, is_closed=True, flags=flags)
                _, scene = self.run_model(self.save_cad(doc), options)
                self.assertAlmostEqual(sum(mesh.volume for mesh in scene.geometry.values()), volume, places=4)
        doc, options = self.cad()
        hatch = doc.modelspace().add_hatch()
        boundary = hatch.paths.add_edge_path()
        boundary.add_line((0, 0), (1, 0))
        boundary.add_line((1, 0), (1, 1))
        boundary.add_line((1, 1), (0, 1))
        with self.assertRaises(convert.ConversionError) as caught:
            convert.run("convert", self.save_cad(doc), self.output, options)
        self.assertEqual(caught.exception.code, "engineering_invalid_geometry")
        self.assertEqual(caught.exception.details["issues"][0]["handle"], hatch.dxf.handle)

    def test_native_mesh_faces_and_closed_polymesh_seams(self):
        doc, options = self.cad()
        polymesh = doc.modelspace().add_polymesh(size=(3, 2))
        for m, (x, y) in enumerate([(0, 0), (2, 0), (0, 2)]):
            for n, z in enumerate([0, 3]):
                polymesh.set_mesh_vertex((m, n), (x, y, z))
        polymesh.close(True, False)
        result, scene = self.run_model(self.save_cad(doc), options)
        self.assertEqual(result["triangleCount"], 6)
        self.assertAlmostEqual(sum(m.area for m in scene.geometry.values()), 3 * (4 + math.sqrt(8)), places=5)
        # Explicit native surfaces retain Z instead of using extrusion height.
        np.testing.assert_allclose(scene.extents, [2, 3, 2], atol=1e-6)

        doc, options = self.cad()
        doc.modelspace().add_3dface([(0, 0, 5), (2, 0, 5), (2, 2, 5), (0, 2, 5)])
        mesh = doc.modelspace().add_mesh()
        with mesh.edit_data() as data:
            data.vertices = [(0, 0, 8), (3, 0, 8), (3, 1, 8), (1, 1, 8), (1, 3, 8), (0, 3, 8)]
            data.faces = [(0, 1, 2, 3, 4, 5)]
        result, scene = self.run_model(self.save_cad(doc), options)
        self.assertEqual(result["triangleCount"], 6)
        self.assertAlmostEqual(sum(m.area for m in scene.geometry.values()), 9, places=5)
        np.testing.assert_allclose(scene.bounds, [[0, 5, -3], [3, 8, 0]], atol=1e-6)
        mesh.dxf.subdivision_levels = 1
        with self.assertRaises(convert.ConversionError) as caught:
            convert.run("convert", self.save_cad(doc), self.output, options)
        self.assertEqual(caught.exception.code, "engineering_unsupported_entity")

    def test_large_coordinates_require_centering_before_float32_export(self):
        doc, options = self.cad(height=3)
        entity = doc.modelspace().add_lwpolyline([(1e7, 0), (1e7 + .2, 0), (1e7 + .2, 3), (1e7, 3)], close=True)
        path = self.save_cad(doc)
        with self.assertRaises(convert.ConversionError) as caught:
            convert.run("convert", path, self.output, options)
        self.assertEqual(caught.exception.code, "engineering_precision_loss")
        self.assertEqual(caught.exception.details["issues"][0]["handle"], entity.dxf.handle)
        _, scene = self.run_model(path, {**options, "center": True})
        self.assertAlmostEqual(sum(m.volume for m in scene.geometry.values()), 1.8, places=5)

    def test_array_blocks_attributes_and_expansion_budget(self):
        doc, options = self.cad()
        block = doc.blocks.new("array-part")
        block.add_circle((0, 0), 1)
        insert = doc.modelspace().add_blockref("array-part", (3, 4), dxfattribs={
            "row_count": 2, "column_count": 3, "row_spacing": 4, "column_spacing": 4})
        result, scene = self.run_model(self.save_cad(doc), options)
        self.assertEqual(result["objectCount"], 6)
        self.assertEqual(len(scene.geometry), 6)
        np.testing.assert_allclose(scene.bounds, [[2, 0, -9], [12, 2, -3]], atol=.002)
        insert.add_attrib("PART", "Part A", (3, 4))
        with self.assertRaises(convert.ConversionError) as caught:
            convert.run("convert", self.save_cad(doc), self.output, options)
        self.assertEqual(caught.exception.details["issues"][0]["entityType"], "ATTRIB")
        insert.dxf.row_count = insert.dxf.column_count = 1000
        with self.assertRaises(convert.ConversionError) as caught:
            convert.run("convert", self.save_cad(doc), self.output, options)
        self.assertEqual(caught.exception.code, "engineering_geometry_limit")

    def test_nested_mirrored_nonuniform_circle_transform(self):
        doc, options = self.cad()
        inner = doc.blocks.new("inner")
        inner.add_circle((0, 0), 1)
        outer = doc.blocks.new("outer")
        outer.add_blockref("inner", (2, 0), dxfattribs={"rotation": 90})
        doc.modelspace().add_blockref("outer", (10, 20), dxfattribs={"xscale": -2, "yscale": 3})
        _, scene = self.run_model(self.save_cad(doc), options)
        np.testing.assert_allclose(scene.bounds, [[4, 0, -23], [8, 2, -17]], atol=.003)
        self.assertAlmostEqual(sum(m.volume for m in scene.geometry.values()), 12 * math.pi, delta=.1)

    def test_bulge_curve_is_preserved(self):
        doc, options = self.cad()
        doc.modelspace().add_lwpolyline([(0, 0, 1), (2, 0, 1)], format="xyb", close=True)
        _, scene = self.run_model(self.save_cad(doc), options)
        self.assertAlmostEqual(sum(m.volume for m in scene.geometry.values()), 2 * math.pi, delta=.03)

    def test_open_and_unsupported_entities_fail_with_source_context(self):
        for kind in ("open", "line"):
            with self.subTest(kind=kind):
                doc, options = self.cad()
                entity = (doc.modelspace().add_lwpolyline([(0, 0), (1, 0), (1, 1)]) if kind == "open"
                          else doc.modelspace().add_line((0, 0), (1, 0)))
                with self.assertRaises(convert.ConversionError) as caught:
                    convert.run("convert", self.save_cad(doc), self.output, options)
                issue = caught.exception.details["issues"][0]
                self.assertEqual(issue["layer"], "0")
                self.assertEqual(issue["handle"], entity.dxf.handle)
                self.assertFalse(self.output.joinpath("model.glb").exists())

    def test_missing_mapping_unit_conflict_and_nonplanar_rejected(self):
        doc, options = self.cad("mm")
        doc.modelspace().add_circle((0, 0), 1000)
        path = self.save_cad(doc)
        for changed, expected in [({**options, "layers": []}, "engineering_invalid_options"),
                                  ({**options, "units": "m"}, "engineering_unit_mismatch")]:
            with self.assertRaises(convert.ConversionError) as caught:
                convert.run("convert", path, self.output, changed)
            self.assertEqual(caught.exception.code, expected)
        doc.modelspace().add_circle((0, 0, 100), 50)
        with self.assertRaises(convert.ConversionError) as caught:
            convert.run("convert", self.save_cad(doc), self.output, options)
        self.assertEqual(caught.exception.code, "engineering_invalid_geometry")

    def test_ifc_schemas_units_opening_and_identity(self):
        for schema in ("IFC2X3", "IFC4", "IFC4X3"):
            with self.subTest(schema=schema):
                path = self.directory / f"{schema}.ifc"
                make_ifc(path, schema)
                result, scene = self.run_model(path, {"schemaVersion": 1, "center": True})
                np.testing.assert_allclose(scene.extents, [4, 3, .2], atol=1e-6)
                # 4 x 0.2 x 3 wall minus 1 x 0.2 x 2 opening.
                self.assertAlmostEqual(sum(m.volume for m in scene.geometry.values()), 2.0, places=5)
                self.assertEqual(result["objectCount"], 1)
                self.assertIn("ifc_openings_applied", result["warnings"])
                mesh = next(iter(scene.geometry.values()))
                self.assertEqual(mesh.metadata["ifcGuid"], "0vAyb7Jjf8FQuyGnKdLTT1")
                np.testing.assert_allclose(result["sourceOrigin"], [100, 200, 0], atol=1e-6)

    def test_cli_protocol_inspection_and_nonzero_failure(self):
        path = self.directory / "layout.dxf"
        make_dxf(path)
        options = self.directory / "options.json"
        options.write_text("{}")
        command = [sys.executable, str(Path(convert.__file__)), "--mode", "inspect", "--input", str(path),
                   "--output", str(self.output), "--options", str(options)]
        completed = subprocess.run(command, capture_output=True, text=True, timeout=30)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        events = [json.loads(line) for line in completed.stdout.splitlines()]
        self.assertEqual(events[-1]["progress"], 100)
        inspection = json.loads(self.output.joinpath("result.json").read_text())
        self.assertEqual(inspection["units"], "mm")
        self.assertEqual(len(inspection["layers"]), 5)
        command[command.index("inspect")] = "convert"
        completed = subprocess.run(command, capture_output=True, text=True, timeout=30)
        self.assertNotEqual(completed.returncode, 0)
        self.assertEqual(json.loads(self.output.joinpath("result.json").read_text())["error"]["code"], "engineering_invalid_options")
        self.assertFalse(self.output.joinpath("model.glb").exists())


if __name__ == "__main__":
    unittest.main()
