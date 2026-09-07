# MeshGram: Connections

The replacement commercial is an original 30-second portrait CGI film, not
footage of an actual network test. Human characters, phones, scenery, lights,
camera moves and the encrypted packet are rendered as three-dimensional objects.
The interface is a legible illustration of a confirmed-contact conversation.
No personal conversations, device identifiers, addresses or screenshots are used.

## Story continuity

1. Roman holds his phone on a city terrace.
2. His outgoing invitation reads `Встретимся у кафе?` and is pending.
3. An opaque locked packet follows a route through devices labeled MeshGram.
4. Alina receives the same invitation from Roman.
5. Alina replies `Уже иду!`; the final shot shows that reply on both phones.
6. The closing line is `Технологии соединяют. Общаются люди.`

The route caption states that BLE needs an available MeshGram route. The movie
does not promise forwarding through unrelated phones, unlimited range, zero
latency, or an unbreakable cryptosystem. It does not depict a city field test.

The rejected cat meme and network-outage wording are not in the movie.

## Reproduce

- Blender 4.5.13 LTS, portable official Windows distribution.
- `tools/cinema/build_film.py`: scene construction, world-space mocap retargeting,
  on-device 3D UI geometry, shot staging and frame rendering.
- `tools/cinema/finish_film.py`: original synthesized stereo music and sound
  design, H.264/AAC encoding, fast-start layout and full decode validation.
- Work root: external folder with `models/rocketbox`, `renders/film` and outputs.
- The finishing script uses NumPy, included in Blender's Python distribution.
- The Manrope static 650-weight font is instantiated from the existing variable
  font using FontTools. Blender otherwise selects that font's thin 200 default.
- Heavy binaries, FBX assets, intermediate frames and `.blend` files stay out of git.

Example:

```powershell
blender -b --factory-startup --python tools/cinema/build_film.py -- --work J:/meshgram-cinema --width 1080 --samples 64
python tools/cinema/finish_film.py --work J:/meshgram-cinema --ffmpeg PATH/ffmpeg.exe
```

## Third-party credits

Human avatars and motion capture: Microsoft Rocketbox, copyright (c) 2020
Microsoft, MIT license. Source:
https://github.com/microsoft/Microsoft-Rocketbox
Commit: `0943055db6ec570bcef9f2c8b41c9e5467c808f9`.

- `Male_Adult_01` and `Female_Adult_01`, including their diffuse/normal textures.
- `m_cell_phone_textmessage.max.fbx` and `f_cell_phone_textmessage.max.fbx`.
- The complete notice is retained in `docs/licenses/Rocketbox-MIT.txt`.
- The source repository's third-party assets are not relicensed as MeshGram-owned.

Font: Manrope, copyright 2018 The Manrope Project Authors, SIL OFL 1.1.
Full notice: `docs/licenses/Manrope-OFL.txt`.

Music, phone geometry, scene geometry, typography layout and routing animation:
created specifically for this MeshGram film. No stock music license is needed.

## Publication

The website video and poster URLs use a new cache version. The website label and
description explicitly identify the film as a 3D visualization instead of a
native Android BLE recording. Telegram publication must happen only after
visual inspection and successful full-file decoding. Keep the previous public
post/video intact until the replacement upload is confirmed.
