# Security

## Reporting a vulnerability

Report a vulnerability privately, through GitHub: on this repository's **Security** tab, choose
**Report a vulnerability**. The report reaches the maintainers alone, and nothing about it is
public until a fix is released. Please do not open a public issue for it.

Say what is affected (the module and the version), what an attacker can do, and how to reproduce
it: a file that crashes a reader, a sequence of calls, the platform it happens on. A file Limn reads
from an untrusted source is the most likely place: images, SVG, fonts, sound, video, 3D models and
themes are all read by the toolkit, and each is meant to refuse damaged input with a
`MalformedContentException` rather than crash, hang or exhaust memory.

You will hear back within a week, and be kept told until a fix is released.

## Supported versions

Fixes are made on the latest release. Native payloads that version on their own
(`limn-lunasvg-natives`, `limn-ffmpeg-natives`) are fixed in their own repositories and released
there, and a toolkit release then names the fixed version.
