"""Generate small public examples from geometry, with no customer content."""
from pathlib import Path
import json


def cad_options():
    return {"schemaVersion": 1, "name": "Engineering layout example", "units": "mm", "center": True,
            "layers": [{"name": name, "role": role, "height": height, "base": base}
                       for name, role, height, base in [
                           ("DT_FLOOR", "floor", .2, -.2), ("DT_WALL", "wall", 4, 0),
                           ("DT_COLUMN", "column", 4, 0), ("DT_EQUIPMENT", "equipment", 1.5, 0),
                           ("DT_IGNORE", "ignore", 1, 0)]]}


def make_dxf(path):
    import ezdxf
    doc = ezdxf.new("R2013")
    doc.units = 4
    model = doc.modelspace()
    for layer in cad_options()["layers"]:
        doc.layers.new(layer["name"])

    def rectangle(x, y, width, depth, layer, layout=model):
        return layout.add_lwpolyline([(x, y), (x + width, y), (x + width, y + depth), (x, y + depth)],
                                     close=True, dxfattribs={"layer": layer})

    rectangle(0, 0, 20000, 12000, "DT_FLOOR")
    rectangle(0, 0, 20000, 200, "DT_WALL")
    rectangle(0, 11800, 20000, 200, "DT_WALL")
    rectangle(0, 200, 200, 11600, "DT_WALL")
    # Entrance is an explicit gap in two right-wall occupied outlines.
    rectangle(19800, 200, 200, 4800, "DT_WALL")
    rectangle(19800, 7000, 200, 4800, "DT_WALL")
    for x, y in ((4000, 3000), (16000, 3000), (4000, 9000), (16000, 9000)):
        model.add_circle((x, y), radius=250, dxfattribs={"layer": "DT_COLUMN"})
    equipment = doc.blocks.new("EQUIPMENT_OCCUPIED_FOOTPRINT")
    rectangle(-1000, -500, 2000, 1000, "0", equipment)
    nested = doc.blocks.new("EQUIPMENT_GROUP")
    nested.add_blockref("EQUIPMENT_OCCUPIED_FOOTPRINT", (0, 0))
    model.add_blockref("EQUIPMENT_GROUP", (8000, 6000), dxfattribs={"layer": "DT_EQUIPMENT"})
    model.add_blockref("EQUIPMENT_GROUP", (13000, 6000), dxfattribs={"layer": "DT_EQUIPMENT", "rotation": 90})
    model.add_text("20m x 12m / units mm / heights entered in metres", dxfattribs={"layer": "DT_IGNORE", "height": 250})
    doc.saveas(path)
    return doc


def make_ifc(path, schema="IFC4"):
    import ifcopenshell
    doc = ifcopenshell.file(schema=schema)
    def point(x, y, z):
        return doc.create_entity("IfcCartesianPoint", Coordinates=(float(x), float(y), float(z)))
    axis = doc.create_entity("IfcAxis2Placement3D", Location=point(0, 0, 0))
    context = doc.create_entity("IfcGeometricRepresentationContext", ContextIdentifier="Model", ContextType="Model",
                                CoordinateSpaceDimension=3, Precision=1e-5, WorldCoordinateSystem=axis)
    unit = doc.create_entity("IfcSIUnit", UnitType="LENGTHUNIT", Prefix="MILLI", Name="METRE")
    units = doc.create_entity("IfcUnitAssignment", Units=[unit])
    doc.create_entity("IfcProject", GlobalId="0vAyb7Jjf8FQuyGnKdLTT0", Name="Public conversion fixture",
                      RepresentationContexts=[context], UnitsInContext=units)
    placement = doc.create_entity("IfcLocalPlacement", RelativePlacement=doc.create_entity("IfcAxis2Placement3D", Location=point(100000, 200000, 0)))
    def box(width, thickness, height):
        profile_origin = doc.create_entity("IfcCartesianPoint", Coordinates=(0., 0.))
        profile_position = doc.create_entity("IfcAxis2Placement2D", Location=profile_origin)
        profile = doc.create_entity("IfcRectangleProfileDef", ProfileType="AREA", Position=profile_position, XDim=float(width), YDim=float(thickness))
        extrusion = doc.create_entity("IfcExtrudedAreaSolid", SweptArea=profile, Position=axis,
                                     ExtrudedDirection=doc.create_entity("IfcDirection", DirectionRatios=(0., 0., 1.)), Depth=float(height))
        representation = doc.create_entity("IfcShapeRepresentation", ContextOfItems=context, RepresentationIdentifier="Body",
                                           RepresentationType="SweptSolid", Items=[extrusion])
        return doc.create_entity("IfcProductDefinitionShape", Representations=[representation])
    wall = doc.create_entity("IfcWall", GlobalId="0vAyb7Jjf8FQuyGnKdLTT1", Name="4m wall with 1m doorway",
                             ObjectPlacement=placement, Representation=box(4000, 200, 3000))
    opening = doc.create_entity("IfcOpeningElement", GlobalId="0vAyb7Jjf8FQuyGnKdLTT2", Name="Door opening",
                                ObjectPlacement=placement, Representation=box(1000, 400, 2000))
    doc.create_entity("IfcRelVoidsElement", GlobalId="0vAyb7Jjf8FQuyGnKdLTT3", RelatingBuildingElement=wall, RelatedOpeningElement=opening)
    doc.write(str(path))
    return doc


def main():
    output = Path(__file__).parent / "fixtures"
    output.mkdir(exist_ok=True)
    make_dxf(output / "engineering-layout.dxf")
    make_ifc(output / "sample.ifc")
    (output / "options.json").write_text(json.dumps(cad_options(), indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
