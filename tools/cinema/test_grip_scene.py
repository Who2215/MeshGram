"""Small scene-level grip development render; assets and results stay external."""

import json
import sys
from pathlib import Path

import bpy
from mathutils import Vector

sys.path.insert(0, str(Path(__file__).resolve().parent))
from grip import PhoneGrip

out = Path(sys.argv[sys.argv.index('--') + 1])
out.mkdir(parents=True, exist_ok=True)
scene = bpy.context.scene
for kind in ('Male_Adult_01', 'Female_Adult_01'):
    col = bpy.data.collections[kind]
    rig = next(o for o in col.objects if o.type == 'ARMATURE' and
               any(b.constraints for b in o.pose.bones))
    device = bpy.data.objects['Personal phone ' + kind]
    grip = PhoneGrip(rig, device, col)
    grip.set_curl({1: [-20, 20, 25], 2: [-20, 30, 35], 3: [-20, 20, 20], 4: [-25, 0, 10]})
    grip.translate_phone((-.006, 0, 0))
    scene.frame_set(428)
    grip.update()
    for collection in scene.collection.children:
        collection.hide_render = collection != col
    cam = bpy.data.objects['Human portrait ' + kind]
    for side, offset in [('back', (.16, .08, -.36)), ('front', (-.16, .09, .36))]:
        target = device.matrix_world.translation
        cam.location = target + device.matrix_world.to_quaternion() @ Vector(offset)
        cam.rotation_euler = (target - cam.location).to_track_quat('-Z', 'Y').to_euler()
        cam.data.lens = 60
        cam.data.dof.use_dof = False
        scene.camera = cam
        scene.render.resolution_x, scene.render.resolution_y = 900, 900
        scene.render.resolution_percentage = 100
        scene.eevee.taa_render_samples = 32
        scene.render.filepath = str(out / f'{kind}-{side}.png')
        bpy.ops.render.render(write_still=True)
    mesh = next(o for o in col.objects if o.type == 'MESH' and o.vertex_groups)
    ev = mesh.evaluated_get(bpy.context.evaluated_depsgraph_get())
    inv = device.matrix_world.inverted() @ ev.matrix_world
    points = [list(inv @ v.co) for v in ev.data.vertices]
    (out / f'{kind}-vertices.json').write_text(json.dumps(points))
