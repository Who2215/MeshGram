"""Inspect an existing film scene without rebuilding or overwriting it."""

import argparse
import json
import sys
from pathlib import Path

import bpy
from mathutils import Euler, Matrix, Vector

parser = argparse.ArgumentParser()
parser.add_argument('--output-dir', required=True, type=Path)
parser.add_argument('--frame', type=int, default=428)
parser.add_argument('--legacy', action='store_true')
opt = parser.parse_args(sys.argv[sys.argv.index('--') + 1:])
opt.output_dir.mkdir(parents=True, exist_ok=True)
scene = bpy.context.scene
report = {}
for kind in ('Male_Adult_01', 'Female_Adult_01'):
    col = bpy.data.collections[kind]
    rig = next(o for o in col.objects if o.type == 'ARMATURE' and
               any(b.constraints for b in o.pose.bones))
    hand = rig.pose.bones['Bip01 R Hand']
    device = bpy.data.objects['Personal phone ' + kind]
    scene.frame_set(400)
    bpy.context.view_layer.update()
    if opt.legacy:
        center = rig.matrix_world @ hand.head.lerp(hand.tail, .35) + Vector((0, -.016, .014))
        desired = Matrix.Translation(center) @ Euler((0.6981317, 0, 3.1415927)).to_matrix().to_4x4()
        grip = (rig.matrix_world @ hand.matrix).inverted() @ desired
    scene.frame_set(opt.frame)
    bpy.context.view_layer.update()
    if opt.legacy:
        device.matrix_world = rig.matrix_world @ hand.matrix @ grip
    bpy.context.view_layer.update()
    inv = device.matrix_world.inverted()
    report[kind] = {b.name: {'head': list(inv @ rig.matrix_world @ b.head),
                           'tail': list(inv @ rig.matrix_world @ b.tail)}
                    for b in rig.pose.bones if 'R Hand' in b.name or 'R Finger' in b.name}
    for collection in scene.collection.children:
        collection.hide_render = collection != col
    cam = bpy.data.objects['Human portrait ' + kind]
    target = device.matrix_world.translation
    cam.location = target + device.matrix_world.to_quaternion() @ Vector((.20, .08, -.38))
    cam.rotation_euler = (target - cam.location).to_track_quat('-Z', 'Y').to_euler()
    cam.data.lens = 60
    cam.data.dof.use_dof = False
    scene.camera = cam
    scene.render.resolution_x, scene.render.resolution_y = 900, 900
    scene.render.resolution_percentage = 100
    scene.eevee.taa_render_samples = 32
    scene.render.filepath = str(opt.output_dir / f'{kind}-{opt.frame}.png')
    bpy.ops.render.render(write_still=True)
(opt.output_dir / 'bones.json').write_text(json.dumps(report, indent=2))
