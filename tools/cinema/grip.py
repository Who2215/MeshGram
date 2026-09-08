"""Hand-local contact rig. Mocap drives the wrist, not independent grip digits."""

import math

import bpy
from mathutils import Matrix, Vector


class PhoneGrip:
    def __init__(self, rig, device, collection):
        self.rig, self.device = rig, device
        self.hand = rig.pose.bones['Bip01 R Hand']
        self.targets = {}
        self.reference = {}
        bpy.context.scene.frame_set(400)
        bpy.context.view_layer.update()
        hand_world = rig.matrix_world @ self.hand.matrix
        self.anchor = bpy.data.objects.new(device.name + ' grip anchor', None)
        collection.objects.link(self.anchor)
        self.anchor.matrix_world = hand_world
        for bone in rig.pose.bones:
            if not bone.name.startswith('Bip01 R Finger'):
                continue
            # Use neutral anatomy, not an already curled mocap frame.
            local = rig.data.bones['Bip01 R Hand'].matrix_local.inverted() @ rig.data.bones[bone.name].matrix_local
            self.reference[bone.name] = local.copy()
            target = bpy.data.objects.new(bone.name + ' contact target ' + rig.name, None)
            collection.objects.link(target)
            target.parent = self.anchor
            target.matrix_local = local
            self.targets[bone.name] = target
            for con in list(bone.constraints):
                bone.constraints.remove(con)
            con = bone.constraints.new('COPY_TRANSFORMS')
            con.target = target
            con.target_space = con.owner_space = 'WORLD'
        # Anatomical axes use knuckle positions, not FBX bone-tail conventions.
        points = {name: hand_world @ m.translation for name, m in self.reference.items()}
        wrist = hand_world.translation
        index = points['Bip01 R Finger1']
        pinky = points['Bip01 R Finger4']
        up = (index - pinky).normalized()
        across = ((index + pinky) * .5 - wrist).normalized()
        across = (across - up * across.dot(up)).normalized()
        normal = across.cross(up).normalized()
        if normal.z < 0:
            normal = -normal
            across = -across
        self.axes = Matrix((across, up, normal)).transposed()
        center = (index + pinky) * .5 - across * .018 + up * .015 + normal * .018
        world = self.axes.to_4x4()
        world.translation = center
        self.local_phone = hand_world.inverted() @ world
        self.local_axis = hand_world.to_3x3().inverted() @ up
        self.local_axis.normalize()
        self.set_curl([0, 0, 0])

    def set_curl(self, angles):
        for digit in range(1, 5):
            names = [f'Bip01 R Finger{digit}{suffix}' for suffix in ('', '1', '2')]
            transform = Matrix.Identity(4)
            digit_angles = angles[digit] if isinstance(angles, dict) else angles
            for name, angle in zip(names, digit_angles):
                pivot = transform @ self.reference[name].translation
                axis = transform.to_3x3() @ self.local_axis
                turn = Matrix.Translation(pivot) @ Matrix.Rotation(math.radians(angle), 4, axis) @ Matrix.Translation(-pivot)
                transform = turn @ transform
                self.targets[name].matrix_local = transform @ self.reference[name]
        self.update()

    def update(self):
        self.anchor.matrix_world = self.rig.matrix_world @ self.hand.matrix
        self.device.matrix_world = self.anchor.matrix_world @ self.local_phone
        bpy.context.view_layer.update()

    def translate_phone(self, offset):
        self.local_phone = self.local_phone @ Matrix.Translation(Vector(offset))
        self.update()
