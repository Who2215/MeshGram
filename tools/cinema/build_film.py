"""Render an original, fully 3D MeshGram commercial with Blender 4.5 LTS.

External MIT assets stay outside the repository. See docs/CINEMA.md.
Run: blender -b --factory-startup --python build_film.py -- --work J:/meshgram-cinema --preview
"""

import argparse
import math
import random
import sys
from pathlib import Path

import bpy
from mathutils import Matrix, Vector

args = argparse.ArgumentParser()
args.add_argument('--work', type=Path, required=True)
args.add_argument('--preview', action='store_true')
args.add_argument('--start', type=int, default=1)
args.add_argument('--end', type=int, default=720)
args.add_argument('--width', type=int, default=720)
args.add_argument('--samples', type=int, default=48)
opt = args.parse_args(sys.argv[sys.argv.index('--') + 1:])
ROOT = opt.work
ASSETS = ROOT / 'models/rocketbox/Assets'
OUT = ROOT / 'renders/film'
OUT.mkdir(parents=True, exist_ok=True)
FONT_PATH = Path(__file__).resolve().parents[2] / 'site/assets/Manrope-Semibold.ttf'
FPS, DURATION = 24, 30
CYAN = (.08, .83, .91)
INK = (.009, .018, .035)
WHITE = (.88, .96, 1)
WARM = (1, .46, .19)

bpy.ops.object.select_all(action='SELECT')
bpy.ops.object.delete(use_global=False)
scene = bpy.context.scene
scene.render.engine = 'BLENDER_EEVEE_NEXT'
scene.eevee.taa_render_samples = opt.samples
scene.render.resolution_x = opt.width
scene.render.resolution_y = round(opt.width * 16 / 9)
scene.render.resolution_percentage = 100
scene.render.fps = FPS
scene.render.image_settings.file_format = 'PNG'
scene.render.image_settings.color_mode = 'RGB'
scene.render.film_transparent = False
scene.view_settings.view_transform = 'AgX'
scene.view_settings.look = 'AgX - Medium High Contrast'
scene.world.use_nodes = True
scene.world.node_tree.nodes['Background'].inputs[0].default_value = (.028, .045, .09, 1)
scene.world.node_tree.nodes['Background'].inputs[1].default_value = .32
scene.render.image_settings.compression = 15
scene.use_nodes = True
nt = scene.node_tree
nt.nodes.clear()
rl = nt.nodes.new('CompositorNodeRLayers')
gl = nt.nodes.new('CompositorNodeGlare')
gl.glare_type = 'FOG_GLOW'
gl.quality = 'HIGH'
gl.threshold = 1.6
gl.size = 7
gl.mix = -.92
co = nt.nodes.new('CompositorNodeComposite')
nt.links.new(rl.outputs['Image'], gl.inputs['Image'])
nt.links.new(gl.outputs['Image'], co.inputs['Image'])
FONT = bpy.data.fonts.load(str(FONT_PATH))
COL = None


def collection(name):
    global COL
    COL = bpy.data.collections.new(name)
    scene.collection.children.link(COL)
    return COL


def own(ob):
    for c in list(ob.users_collection):
        c.objects.unlink(ob)
    COL.objects.link(ob)
    return ob


def mat(name, color, metal=0, rough=.4, emission=0):
    m = bpy.data.materials.new(name)
    m.diffuse_color = (*color, 1)
    m.use_nodes = True
    p = m.node_tree.nodes.get('Principled BSDF')
    p.inputs['Base Color'].default_value = (*color, 1)
    p.inputs['Metallic'].default_value = metal
    p.inputs['Roughness'].default_value = rough
    if name.startswith(('UI ', 'OLED')):
        e = m.node_tree.nodes.new('ShaderNodeEmission')
        e.inputs[0].default_value = (*color, 1)
        e.inputs[1].default_value = 1
        m.node_tree.links.new(e.outputs[0], m.node_tree.nodes['Material Output'].inputs['Surface'])
    if emission:
        p.inputs['Emission Color'].default_value = (*color, 1)
        p.inputs['Emission Strength'].default_value = emission
    return m


def box(name, loc, size, material, bevel=0, parent=None):
    if size[2] < .001 and bevel > 0:
        w, h, _ = size
        r = min(bevel, w / 2, h / 2)
        verts = []
        for cx, cy, start in [(w/2-r, h/2-r, 0), (-w/2+r, h/2-r, 90),
                               (-w/2+r, -h/2+r, 180), (w/2-r, -h/2+r, 270)]:
            for i in range(9):
                a = math.radians(start + i * 90 / 8)
                verts.append((cx + r * math.cos(a), cy + r * math.sin(a), 0))
        mesh = bpy.data.meshes.new(name)
        mesh.from_pydata(verts, [], [tuple(range(len(verts)))])
        mesh.update()
        ob = bpy.data.objects.new(name, mesh)
        COL.objects.link(ob)
        ob.location = loc
        ob.data.materials.append(material)
        ob.parent = parent
        return ob
    bpy.ops.mesh.primitive_cube_add(size=1, location=loc)
    ob = own(bpy.context.object)
    ob.name = name
    ob.dimensions = size
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if bevel:
        mod = ob.modifiers.new('Precision machined radius', 'BEVEL')
        mod.width = bevel
        mod.segments = 5
        ob.modifiers.new('Weighted corner normals', 'WEIGHTED_NORMAL')
    ob.data.materials.append(material)
    ob.parent = parent
    return ob


def empty(name):
    ob = bpy.data.objects.new(name, None)
    COL.objects.link(ob)
    return ob


def sphere(name, loc, radius, material, parent=None):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=16, ring_count=8, radius=radius, location=loc)
    ob = own(bpy.context.object)
    ob.name = name
    ob.data.materials.append(material)
    for p in ob.data.polygons:
        p.use_smooth = True
    ob.parent = parent
    return ob


def tube(name, points, radius, material, parent=None):
    curve = bpy.data.curves.new(name, 'CURVE')
    curve.dimensions = '3D'
    curve.bevel_depth = radius
    curve.bevel_resolution = 3
    spl = curve.splines.new('POLY')
    spl.points.add(len(points) - 1)
    for p, co in zip(spl.points, points):
        p.co = (*co, 1)
    ob = bpy.data.objects.new(name, curve)
    COL.objects.link(ob)
    ob.data.materials.append(material)
    ob.parent = parent
    return ob


def text(name, content, loc, size, material, align='CENTER', parent=None):
    curve = bpy.data.curves.new(name, 'FONT')
    curve.body = content
    curve.font = FONT
    curve.size = size
    curve.align_x = align
    curve.align_y = 'CENTER'
    curve.space_line = 1.15
    ob = bpy.data.objects.new(name, curve)
    COL.objects.link(ob)
    ob.location = loc
    ob.data.materials.append(material)
    ob.parent = parent
    return ob


def light(name, loc, target, energy, color, size):
    bpy.ops.object.light_add(type='AREA', location=loc)
    ob = own(bpy.context.object)
    ob.name = name
    ob.data.energy, ob.data.color, ob.data.size = energy, color, size
    ob.data.shape = 'DISK'
    ob.rotation_euler = (Vector(target) - ob.location).to_track_quat('-Z', 'Y').to_euler()
    return ob


def camera(name, loc, target, lens=55, fstop=3.2):
    bpy.ops.object.camera_add(location=loc)
    ob = own(bpy.context.object)
    ob.name = name
    ob.data.lens = lens
    ob.data.sensor_width = 36
    ob.data.sensor_fit = 'HORIZONTAL'
    ob.data.dof.use_dof = True
    ob.data.dof.aperture_fstop = fstop
    focus(ob, loc, target)
    return ob


def focus(cam, loc, target):
    cam.location = loc
    delta = Vector(target) - cam.location
    if cam.name == 'Product camera':
        forward = delta.normalized()
        right = forward.cross(Vector((0, 1, 0))).normalized()
        up = right.cross(forward)
        cam.rotation_euler = Matrix((right, up, -forward)).transposed().to_euler()
    else:
        cam.rotation_euler = delta.to_track_quat('-Z', 'Y').to_euler()
    cam.data.dof.focus_distance = delta.length


def logo(parent, loc, scale, material):
    pts = [(math.cos(i * math.tau / 6), math.sin(i * math.tau / 6)) for i in range(6)]
    for i, (x, y) in enumerate(pts):
        p = (loc[0] + x * scale, loc[1] + y * scale, loc[2])
        q = pts[(i + 2) % 6]
        tube('MeshGram network spoke', [p, loc, (loc[0] + q[0] * scale, loc[1] + q[1] * scale, loc[2])], scale * .025, material, parent)
        sphere('MeshGram vertex', p, scale * .10, material, parent)


metal = mat('Brushed graphite titanium', (.09, .12, .15), .85, .24)
glass = mat('Obsidian glass', (.004, .009, .013), .42, .11)
screen = mat('OLED midnight', (.009, .019, .033), 0, .4, .8)
uiwhite = mat('UI pearl', WHITE, 0, .6, .8)
uimute = mat('UI slate', (.25, .43, .52), 0, .7, .8)
uicyan = mat('UI glacier', CYAN, 0, .5, .9)
uidark = mat('UI ink', INK, 0, .9, .5)
uibubble = mat('UI outgoing', (.030, .145, .19), 0, .7, .7)
uiincoming = mat('UI incoming', (.05, .09, .15), 0, .7, .7)
neon = mat('Cyan optical fiber', CYAN, .1, .3, 4)
amber = mat('Amber warm light', WARM, .1, .3, 3)
stone = mat('Pearl concrete', (.32, .39, .42), .05, .6)
darkstone = mat('Basalt concrete', (.021, .038, .054), .15, .5)
wood = mat('Smoked oak', (.12, .055, .025), 0, .65)
foliage = mat('Olive leaves', (.023, .095, .047), 0, .75)


def phone(name, receiver=False, relay=False):
    root = empty(name)
    box(name + ' titanium unibody', (0, 0, 0), (.081, .170, .009), metal, .005, root)
    box(name + ' front glass', (0, 0, .0041), (.078, .167, .002), glass, .006, root)
    box(name + ' OLED', (0, 0, .00525), (.073, .158, .0006), screen, .004, root)
    box(name + ' volume', (-.0411, .030, 0), (.0018, .023, .003), metal, .0007, root)
    box(name + ' power', (.0411, .023, 0), (.0018, .015, .003), metal, .0007, root)
    box(name + ' gesture inset', (0, -.075, .0058), (.021, .0008, .0001), uiwhite, .0003, root)
    sphere(name + ' selfie lens', (0, .075, .0058), .0014, glass, root)
    text('Time', '19:24', (-.028, .074, .006), .0025, uiwhite, 'LEFT', root)
    text('Battery', '87%', (.027, .074, .006), .0025, uiwhite, 'RIGHT', root)
    # A camera island on the back makes the devices real objects, not screen cards.
    box(name + ' camera island', (-.019, .054, -.005), (.031, .041, .003), glass, .005, root)
    for x, y in [(-.025, .063), (-.013, .046)]:
        sphere(name + ' rear lens', (x, y, -.007), .0055, metal, root)
        sphere(name + ' lens optical glass', (x, y, -.009), .0038, glass, root)
    if relay:
        logo(root, (0, .020, .0065), .018, uicyan)
        text('Relay brand', 'MeshGram', (0, -.008, .006), .007, uiwhite, parent=root)
        text('Relay mode', 'РЕТРАНСЛЯЦИЯ', (0, -.025, .006), .0035, uicyan, parent=root)
        text('Relay privacy', 'Зашифрованный пакет', (0, -.037, .006), .0028, uimute, parent=root)
        return root, None, []
    contact = 'Роман' if receiver else 'Алина'
    text('Back', '‹', (-.030, .059, .006), .010, uicyan, parent=root)
    text('Contact', contact, (-.020, .062, .006), .006, uiwhite, 'LEFT', root)
    text('Verified', 'Подтверждённый контакт', (-.020, .053, .006), .0027, uimute, 'LEFT', root)
    tube('Divider', [(-.032, .045, .006), (.032, .045, .006)], .00012, uimute, root)
    text('Date', 'Сегодня', (0, .036, .006), .0027, uimute, parent=root)
    x = -.003 if receiver else .004
    box('Invitation bubble', (x, .008, .006), (.057, .031, .0002), uiincoming if receiver else uibubble, .004, root)
    text('Invitation exact on both phones', 'Встретимся\nу кафе?', (x - .021, .012, .0065), .0048, uiwhite, 'LEFT', root)
    status = text('Delivery state', '19:24' if receiver else 'Отправляется', (x + .022, -.0035, .0065), .0024, uimute, 'RIGHT', root)
    reply = []
    x2 = .005 if receiver else -.004
    reply.append(box('Reply bubble', (x2, -.026, .006), (.054, .021, .0002), uibubble if receiver else uiincoming, .004, root))
    reply.append(text('Reply exact on both phones', 'Уже иду!', (x2 - .020, -.023, .0065), .0048, uiwhite, 'LEFT', root))
    reply.append(text('Reply time', '19:25', (x2 + .021, -.032, .0065), .0024, uimute, 'RIGHT', root))
    box('Composer', (0, -.059, .006), (.064, .014, .0002), uiincoming, .005, root)
    text('Composer placeholder', 'Сообщение', (-.024, -.059, .0066), .0033, uimute, 'LEFT', root)
    text('Send arrow', '↑', (.024, -.059, .0068), .007, uicyan, parent=root)
    return root, status, reply


def setup_person(kind, motion_name):
    old = set(bpy.data.objects)
    folder = ASSETS / 'Avatars/Adults' / kind
    bpy.ops.import_scene.fbx(filepath=str(folder / 'Export' / (kind + '.fbx')))
    avatar_objects = set(bpy.data.objects) - old
    rig = next(o for o in avatar_objects if o.type == 'ARMATURE')
    rig.animation_data_clear()
    for ob in avatar_objects:
        own(ob)
        if ob.type == 'MESH':
            for poly in ob.data.polygons:
                poly.use_smooth = True
            sub = ob.modifiers.new('Silhouette refinement', 'SUBSURF')
            sub.levels = 1
            sub.render_levels = 1
            for m in ob.data.materials:
                for n in m.node_tree.nodes:
                    if n.type == 'TEX_IMAGE' and n.image:
                        file = folder / 'Textures' / Path(n.image.filepath).name
                        if file.exists():
                            n.image.filepath = str(file)
                            n.image.reload()
                    if n.type == 'BSDF_PRINCIPLED':
                        for link in list(n.inputs['Roughness'].links):
                            m.node_tree.links.remove(link)
                        n.inputs['Roughness'].default_value = .65
                        n.inputs['Specular IOR Level'].default_value = .25
                        if 'head' in m.name:
                            n.inputs['Subsurface Weight'].default_value = .07
                        if 'opacity' in m.name:
                            n.inputs['Specular IOR Level'].default_value = .05
                if 'opacity' in m.name:
                    m.surface_render_method = 'DITHERED'
    before = set(bpy.data.objects)
    bpy.ops.import_scene.fbx(filepath=str(ASSETS / 'Animations/all_animations_max_motextr_static' / motion_name))
    motion_objects = set(bpy.data.objects) - before
    motion = next(o for o in motion_objects if o.type == 'ARMATURE')
    for ob in motion_objects:
        own(ob)
    # FBX rest axes differ between model and mocap files. World-space retargeting
    # avoids the twisted elbows produced by copying local action channels.
    for bone in rig.pose.bones:
        if bone.name in motion.pose.bones:
            con = bone.constraints.new('COPY_TRANSFORMS')
            con.target, con.subtarget = motion, bone.name
            con.target_space = con.owner_space = 'WORLD'
    scene.frame_set(400)
    bpy.context.view_layer.update()
    return rig


def surroundings(female=False):
    rng = random.Random(112 if female else 22)
    box('Stone terrace', (0, 0, -.12), (40, 40, .22), darkstone, .04)
    for x in range(-5, 6):
        tube('Pavement joint', [(x * .8, -6, -.005), (x * .8, 10, -.005)], .0015, stone)
    for y in range(-6, 10):
        tube('Pavement joint', [(-5, y * .8, -.004), (5, y * .8, -.004)], .0015, stone)
    # Three-dimensional facades, mullions and lit windows create honest parallax.
    for x in [-7, -4.5, -2.2, 1, 3.7, 6.5]:
        height = rng.uniform(3.5, 7)
        box('City facade', (x, 7, height / 2), (2, 1, height), darkstone, .035)
        for z in range(1, int(height * 2)):
            for xx in [-.64, 0, .64]:
                if rng.random() > .26:
                    box('Warm city window', (x + xx, 6.49, z * .45), (.32, .018, .23), amber if rng.random() > .35 else uimute, .012)
    for x in [-2.3, 2.3]:
        box('Concrete planter', (x, 2.8, .22), (.65, .65, .45), stone, .045)
        tube('Tree trunk', [(x, 2.8, .4), (x + .08, 2.8, 2.1)], .045, wood)
        for _ in range(18):
            sphere('Sculptural foliage', (x + rng.uniform(-.48, .48), 2.8 + rng.uniform(-.4, .4), rng.uniform(1.6, 2.6)), rng.uniform(.12, .3), foliage)
    box('Timber bench', (1.45, 1.3, .48), (1.7, .40, .08), wood, .03)
    for x in [.8, 2.1]:
        box('Bench legs', (x, 1.3, .23), (.08, .28, .46), metal, .015)
    for x in [-1.8, 1.8]:
        tube('Terrace fixture', [(x, 2, 0), (x, 2, 2.8)], .025, metal)
        tube('Terrace vertical light', [(x, 1.97, 1), (x, 1.97, 2.6)], .008, amber)
        light('Architectural warmth', (x, 2, 2.7), (0, 0, 1), 110, WARM, 1)
    light('Large soft portrait key', (1, -3, 3), (0, 0, 1.3), 350, (1, .83, .67), 3)
    light('Cool skyline rim', (-2, 1, 3), (0, 0, 1.2), 650, (.22, .7, 1), 2)
    light('Warm back rim', (2, 2, 2.5), (0, 0, 1.3), 450, (1, .48, .22), 2)
    light('Eye fill', (0, -2, 2), (0, 0, 1.5), 50, WHITE, 1.5)


def person_scene(kind, motion, receiver):
    col = collection(kind)
    rig = setup_person(kind, motion)
    surroundings(receiver)
    device, status, reply = phone('Personal phone ' + kind, receiver)
    hand = rig.pose.bones['Bip01 R Hand']
    hand_world = rig.matrix_world @ hand.matrix
    center = rig.matrix_world @ hand.head.lerp(hand.tail, .35) + Vector((0, -.016, .014))
    from mathutils import Euler
    desired = Matrix.Translation(center) @ Euler((math.radians(40), 0, math.pi)).to_matrix().to_4x4()
    local_grip = hand_world.inverted() @ desired
    cam = camera('Human portrait ' + kind, (1.1, -2.5, 1.8), (0, -.02, 1.25), 80, 3.2)
    return {'col': col, 'rig': rig, 'hand': hand, 'phone': device, 'grip': local_grip, 'camera': cam, 'status': status, 'reply': reply}


male = person_scene('Male_Adult_01', 'm_cell_phone_textmessage.max.fbx', False)
female = person_scene('Female_Adult_01', 'f_cell_phone_textmessage.max.fbx', True)

studio = collection('Product macro studio')
box('Endless studio plinth', (0, 0, -.42), (200, 200, .2), screen, .02)
for i in range(4):
    radius = .16 + i * .055
    tube('Studio orbital sculpture', [(radius * math.cos(j * math.tau / 96), radius * math.sin(j * math.tau / 96), -.25 - i * .01) for j in range(97)], .0007, uimute)
light('Studio softbox', (-1.5, -1.5, 2), (0, 0, 0), 180, WHITE, 2)
light('Studio cyan strip', (1, .3, 1), (0, 0, 0), 130, CYAN, 1.5)
light('Studio warm strip', (-.8, .5, 1.2), (0, 0, 0), 110, WARM, .8)
product_a, status_a, reply_a = phone('Roman hero', False)
product_b, status_b, reply_b = phone('Alina hero', True)
studio_cam = camera('Product camera', (.13, -.2, .7), (0, 0, 0), 80, 8)
studio_cam.data.dof.use_dof = False

network = collection('Encrypted three dimensional route')
box('District plinth', (0, 0, -.25), (8, 8, .40), darkstone, .2)
light('District large softbox', (-3, -4, 8), (0, 0, 0), 850, WHITE, 6)
light('District cyan rim', (4, 3, 6), (0, 0, 0), 650, CYAN, 4)
rng = random.Random(34)
for i in range(34):
    x, y = rng.uniform(-3.5, 3.5), rng.uniform(-3.4, 3.4)
    if abs(y) < 1.1:
        continue
    h = rng.uniform(.15, .65)
    box('Miniature district building', (x, y, h / 2), (rng.uniform(.2, .48), .35, h), stone, .025)
    for z in range(1, int(h / .09)):
        box('Miniature lit windows', (x, y - .178, z * .09), (.15, .005, .02), amber, .004)
route_points = [Vector((-1, -1.9, .3)), Vector((1.0, -.65, .38)), Vector((-1.05, .85, .42)), Vector((.85, 2.3, .3))]
network_phones = []
for i, p in enumerate(route_points):
    device, _, _ = phone('MeshGram route node ' + str(i), relay=True)
    device.location = p
    device.scale = (5.0,) * 3
    device.rotation_euler = (math.radians(23), 0, math.radians(-15 + i * 9))
    network_phones.append(device)
    box('Node pedestal', (p.x, p.y, .09), (.62, .82, .12), metal, .07)
    label = text('Node label', ['Роман', 'MeshGram 01', 'MeshGram 02', 'Алина'][i], (p.x, p.y - .65, .02), .12, uiwhite)
    for j in range(33):
        a = math.tau * j / 32
        # Geometry rings, not animated fake radio coverage claims.
        if j == 0:
            ring = []
        ring.append((p.x + .49 * math.cos(a), p.y + .49 * math.sin(a), .01))
    tube('Active app node rim', ring, .007, neon)


def curve_point(index, v):
    a, b = route_points[index], route_points[index + 1]
    p = a.lerp(b, v)
    p.z += .32 + math.sin(math.pi * v) * .4
    return p


for i in range(3):
    tube('Encrypted link ' + str(i), [curve_point(i, j / 64) for j in range(65)], .009, uimute)
packet = empty('Authenticated ciphertext packet')
packet_shell = box('Packet graphite capsule', (0, 0, 0), (.18, .18, .18), metal, .035, packet)
for z in [-.09, .09]:
    tube('Packet light edge', [(-.09, -.09, z), (.09, -.09, z), (.09, .09, z), (-.09, .09, z), (-.09, -.09, z)], .008, neon, packet)
lock = box('Ciphertext lock', (0, 0, .096), (.072, .057, .012), uicyan, .008, packet)
tube('Lock shackle', [(-.021, .02, .106), (-.021, .06, .106), (0, .072, .106), (.021, .06, .106), (.021, .02, .106)], .006, uicyan, packet)
sphere('Lock keyhole', (0, -.004, .106), .008, uidark, packet)
trail = [sphere('Optical packet trail', (0, 0, 0), .014 * (1 - i / 25), neon) for i in range(18)]
network_cam = camera('Route dolly', (3, -5.8, 6.5), (0, 0, .3), 50, 9)
network_cam.data.dof.use_dof = False

graphics = collection('Camera typography')
title = text('Editorial title', '', (0, .225, -1), .045, uiwhite)
subtitle = text('Editorial subtitle', '', (0, .15, -1), .015, uicyan)
footnote = text('Truthful visualization caption', '', (0, -.248, -1), .010, uiwhite)
wordmark = text('MeshGram end card', 'MeshGram', (0, .19, -1), .060, uiwhite)
tagline = text('Human brand promise', 'Технологии соединяют.\nОбщаются люди.', (0, .10, -1), .024, uiwhite)
website = text('Download address', 'who2215.github.io/MeshGram', (0, -.208, -1), .016, uicyan)
download = text('Download label', 'ПОПРОБУЙ НА ANDROID', (0, -.165, -1), .014, uiwhite)
graphic_objects = [title, subtitle, footnote, wordmark, tagline, website, download]
collections = [male['col'], female['col'], studio, network]


def smooth(v):
    v = min(1, max(0, v))
    return v * v * (3 - 2 * v)


def hierarchy_visible(root, visible):
    root.hide_render = not visible
    for child in root.children_recursive:
        child.hide_render = not visible


def render_frame(frame):
    t = (frame - 1) / FPS
    scene.frame_set(380 + int(t * FPS))
    # Preserve the original texting performance for each human shot.
    if 15 <= t < 20:
        scene.frame_set(380 + int((t - 15) * FPS))
    for person in [male, female]:
        person['phone'].matrix_world = person['rig'].matrix_world @ person['hand'].matrix @ person['grip']
    for col in collections:
        col.hide_render = True
    for ob in graphic_objects:
        ob.hide_render = True
    for ob in reply_a + reply_b + male['reply'] + female['reply']:
        ob.hide_render = t < 23
    status_a.data.body = 'Отправляется' if t < 15 else 'Прочитано'
    title.data.body = subtitle.data.body = footnote.data.body = ''
    if t < 5:
        male['col'].hide_render = False
        scene.camera = male['camera']
        f = smooth(t / 5)
        focus(scene.camera, (1.20 - .30 * f, -2.35 + .12 * f, 1.72), (-.02, -.04, 1.27))
        title.data.body = 'Есть повод\nвстретиться.'
        subtitle.data.body = 'Начни с сообщения.'
    elif t < 8:
        studio.hide_render = False
        scene.camera = studio_cam
        hierarchy_visible(product_a, True)
        hierarchy_visible(product_b, False)
        product_a.location = (0, 0, 0)
        product_a.rotation_euler = (0, .08 * math.sin(t), -.06)
        f = smooth((t - 5) / 3)
        focus(studio_cam, (.018 - .010 * f, -.015, .36 - .010 * f), (0, .026, 0))
        title.data.body = 'Одно сообщение.'
        subtitle.data.body = 'Только нужному человеку.'
    elif t < 15:
        network.hide_render = False
        scene.camera = network_cam
        progress = min(2.9999, (t - 8) / 7 * 3)
        index, v = int(progress), progress % 1
        packet.location = curve_point(index, v)
        packet.rotation_euler = (.10 * math.sin(t), .15 * math.cos(t), .15 * t)
        for i, ob in enumerate(trail):
            tail = max(0, progress - .018 * (i + 1))
            ob.location = curve_point(min(2, int(tail)), tail % 1)
        f = smooth((t - 8) / 7)
        focus(network_cam, (2.0 - 1.3 * f, -5.6, 7.8), (.05, .25, .1))
        title.data.body = 'Свой маршрут.'
        subtitle.data.body = 'Через устройства с MeshGram.'
        footnote.data.body = '3D-схема: для BLE нужен доступный маршрут.'
    elif t < 20:
        female['col'].hide_render = False
        scene.camera = female['camera']
        f = smooth((t - 15) / 5)
        focus(scene.camera, (.95 - .22 * f, -2.30 + .10 * f, 1.72), (0, -.035, 1.30))
        title.data.body = 'На другом конце —\nтвой человек.'
        subtitle.data.body = 'Сообщение доставлено.'
    elif t < 24:
        studio.hide_render = False
        scene.camera = studio_cam
        hierarchy_visible(product_a, False)
        hierarchy_visible(product_b, True)
        product_b.location = (0, 0, 0)
        product_b.rotation_euler = (0, -.05 * math.sin(t), .04)
        focus(studio_cam, (-.016, -.013, .35), (0, .026, 0))
        title.data.body = '«Уже иду!»'
        subtitle.data.body = 'Разговор продолжается.'
    else:
        studio.hide_render = False
        scene.camera = studio_cam
        hierarchy_visible(product_a, True)
        hierarchy_visible(product_b, True)
        product_a.location = (-.047, -.008, 0)
        product_b.location = (.047, -.011, .015)
        product_a.rotation_euler = (0, .12, .10)
        product_b.rotation_euler = (0, -.12, -.10)
        focus(studio_cam, (.008 * math.sin(t * .15), -.025, .50), (0, .023, 0))
        for ob in [wordmark, tagline, website, download]:
            ob.hide_render = False
        footnote.data.body = '3D-реклама. Интерфейс иллюстрирует сценарий.'
    # Phone hierarchy visibility must not override staged message delivery.
    for ob in reply_a + reply_b + male['reply'] + female['reply']:
        ob.hide_render = t < 23 or (ob in reply_a and t < 24)
    # Scale typography to the camera's actual horizontal view at one metre.
    half = scene.camera.data.sensor_width / (2 * scene.camera.data.lens)
    factor = half / .30
    for ob in graphic_objects:
        ob.parent = scene.camera
        x, y, z = ob.location
        base_y = {'Editorial title': .405, 'Editorial subtitle': .310,
                  'Truthful visualization caption': -.477, 'MeshGram end card': .415,
                  'Human brand promise': .305, 'Download address': -.420,
                  'Download label': -.365}[ob.name]
        depth = scene.camera.data.dof.focus_distance if scene.camera.data.dof.use_dof else .25
        ob.location = (0, base_y * factor * depth, -depth)
        ob.scale = (factor * depth,) * 3
    for ob in [title, subtitle, footnote]:
        ob.hide_render = not bool(ob.data.body)
    bpy.context.view_layer.update()
    scene.render.filepath = str(OUT / f'{frame:04d}.png')
    bpy.ops.render.render(write_still=True)
    print(f'MESHGRAM_FRAME {frame}/{FPS * DURATION}', flush=True)


scene.frame_start, scene.frame_end = 1, FPS * DURATION
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT / 'meshgram-cinema.blend'))
frames = [49, 145, 241, 325, 409, 505, 565, 649] if opt.preview else range(opt.start, opt.end + 1)
for frame in frames:
    render_frame(frame)
