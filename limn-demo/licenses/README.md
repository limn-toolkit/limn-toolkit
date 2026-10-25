# The licence texts the runnable demo jar carries

`./gradlew :limn-demo:fatJar` builds `limn-demo-all.jar`, the one file attached to every GitHub
release, by unpacking every jar on the demo's runtime classpath, and copies this directory into
it as `META-INF/licenses/`. Most of what it unpacks carries its own licence beside what it covers
(the fonts, the icon pack, the SVG renderer's, ANGLE's and FFmpeg's payloads, the toolkit's CLDR
tables), or is under Apache-2.0 like the toolkit, whose `LICENSE` the jar carries (JSpecify's
annotations, the WebP decoder). Two do neither: JLayer's jar, and LWJGL's, whose natives jars hold
LWJGL's JNI libraries and the native libraries LWJGL builds, with no licence file at all. Their
texts are here.

Every text is its project's own, unchanged, except `stb-LICENSE.txt`, which is cut from the
sources it closes. The digest is the SHA-256 of the file as committed. `NativeLicencesTest` holds
each file to its row, the rows to the LWJGL release `gradle/libs.versions.toml` pins and to the
commits LWJGL's natives jars record, and every native library on the demo's classpath to the texts
it needs; the `fatJar` task reads every file back out of the archive it built.

**Where they come from.** LWJGL's are taken at its tag `3.4.3` (commit
30fac9b95f99cda97312232be25ba55297bf9951), the release the build pins. GLFW, HarfBuzz and OpenAL
Soft are not built from LWJGL's tree: each natives jar records beside the library the commit of the
upstream repository it was built from (`META-INF/<os>/<arch>/org/lwjgl/<module>/<library>.git`),
and their texts are taken at those commits, along with the texts those trees give for code they
compile in from others. LWJGL keeps copies of the three in its own tree, but older ones: its
HarfBuzz text lacks five of the copyright holders HarfBuzz's names at the commit built. When LWJGL
is bumped, the test fails until every text is taken again at the new tag and commits.

| file | covers | taken from | SHA-256 |
| --- | --- | --- | --- |
| `LWJGL-LICENSE.txt` | LWJGL: its classes and its JNI libraries `lwjgl`, `lwjgl_opengl`, `lwjgl_opengles`, `lwjgl_stb`, `lwjgl_tinyfd` | [`LICENSE.md` at LWJGL 3.4.3](https://raw.githubusercontent.com/LWJGL/lwjgl3/3.4.3/LICENSE.md) | `4c2f770897d0cfda4d9a65bb4d6d6983dccfd57d4c7eb74ea5ded0762cbc0e75` |
| `libffi-LICENSE.txt` | libffi, inside LWJGL's core library | [`modules/lwjgl/core/libffi_license.txt` at LWJGL 3.4.3](https://raw.githubusercontent.com/LWJGL/lwjgl3/3.4.3/modules/lwjgl/core/libffi_license.txt) | `d66f6a020ebb611afe64b93270d447aa32a9345d2d3a43d296493e9806b944f8` |
| `liburing-LICENSE.txt` | liburing, inside LWJGL's core library on Linux | [`modules/lwjgl/core/liburing_license.txt` at LWJGL 3.4.3](https://raw.githubusercontent.com/LWJGL/lwjgl3/3.4.3/modules/lwjgl/core/liburing_license.txt) | `1ec30e534aecb8eef3439fe3e36b20be20461bb758ca85107602fdaa3e23d274` |
| `Khronos-LICENSE.txt` | the Khronos Group's headers LWJGL's OpenGL, OpenGL ES and EGL bindings are generated from, in `lwjgl_opengl`, `lwjgl_opengles` and the EGL classes | [`modules/lwjgl/opengl/khronos_license.txt` at LWJGL 3.4.3](https://raw.githubusercontent.com/LWJGL/lwjgl3/3.4.3/modules/lwjgl/opengl/khronos_license.txt), the same bytes as `opengles/` and `egl/` hold | `f3857759226191860119a14e6cc0420df506c10a5c1b6b2ff8abc8f8d31920fb` |
| `stb-LICENSE.txt` | stb, inside `lwjgl_stb` | the comment that closes each of the nine stb sources in `modules/lwjgl/stb/src/main/c/` at LWJGL 3.4.3, the same in all nine, without its `/*` and `*/` lines; [`stb_truetype.h`](https://raw.githubusercontent.com/LWJGL/lwjgl3/3.4.3/modules/lwjgl/stb/src/main/c/stb_truetype.h) is one | `9407f57d550d1d36974de15c7aad3dbf21e59c4b3312fc762157f05fdea6ff41` |
| `tinyfiledialogs-LICENSE.txt` | tinyfiledialogs, inside `lwjgl_tinyfd` | [`modules/lwjgl/tinyfd/tinyfd_license.txt` at LWJGL 3.4.3](https://raw.githubusercontent.com/LWJGL/lwjgl3/3.4.3/modules/lwjgl/tinyfd/tinyfd_license.txt) | `43eedc0b74269e842aebc6d28286bcd3f4a464aed058687dc6a1b3835e3593f4` |
| `GLFW-LICENSE.txt` | GLFW: `glfw`, and `glfw_async` on macOS | [`LICENSE.md` at GLFW d9d6f0f1f967807ffade6598ea9a631ebaf37a56](https://raw.githubusercontent.com/glfw/glfw/d9d6f0f1f967807ffade6598ea9a631ebaf37a56/LICENSE.md) | `149704059b5d0bf551637e50042dd4de9c2cae921021f6636298911e3a5f9462` |
| `HarfBuzz-LICENSE.txt` | HarfBuzz: `harfbuzz`, `harfbuzz-gpu`, `harfbuzz-raster`, `harfbuzz-vector` | [`COPYING` at HarfBuzz 9f2f03173b7fee860cc00d999857d09fa4a362e2](https://raw.githubusercontent.com/harfbuzz/harfbuzz/9f2f03173b7fee860cc00d999857d09fa4a362e2/COPYING) | `ba8f810f2455c2f08e2d56bb49b72f37fcf68f1f4fade38977cfd7372050ad64` |
| `HarfBuzz-USE-LICENSE.txt` | the shaping data HarfBuzz's Universal Shaping Engine tables are generated from, the part of HarfBuzz its `COPYING` says is under another licence | [`src/ms-use/COPYING` at HarfBuzz 9f2f03173b7fee860cc00d999857d09fa4a362e2](https://raw.githubusercontent.com/harfbuzz/harfbuzz/9f2f03173b7fee860cc00d999857d09fa4a362e2/src/ms-use/COPYING) | `c2cfccb812fe482101a8f04597dfc5a9991a6b2748266c47ac91b6a5aae15383` |
| `OpenAL-Soft-LICENSE.txt` | OpenAL Soft: `openal`, `OpenAL.dll`; the GNU Library General Public License, version 2 | [`COPYING` at OpenAL Soft 7f9c6de1162b1d4604b9a2f8bcd6c53021566221](https://raw.githubusercontent.com/kcat/openal-soft/7f9c6de1162b1d4604b9a2f8bcd6c53021566221/COPYING) | `d808ce217e5b611854da622b57ec29fe545584c48bc5352fae72a4b6e5074a15` |
| `OpenAL-Soft-PFFFT-LICENSE.txt` | the modified PFFFT OpenAL Soft compiles in | [`LICENSE-pffft` at OpenAL Soft 7f9c6de1162b1d4604b9a2f8bcd6c53021566221](https://raw.githubusercontent.com/kcat/openal-soft/7f9c6de1162b1d4604b9a2f8bcd6c53021566221/LICENSE-pffft) | `64056328b3e7bc104e24ef96accc1a7abead156f0c02d1b87c97e3f3a28030de` |
| `OpenAL-Soft-fmt-LICENSE.txt` | the {fmt} OpenAL Soft links in | [`fmt-11.2.0/LICENSE` at OpenAL Soft 7f9c6de1162b1d4604b9a2f8bcd6c53021566221](https://raw.githubusercontent.com/kcat/openal-soft/7f9c6de1162b1d4604b9a2f8bcd6c53021566221/fmt-11.2.0/LICENSE) | `07580f2a3b35709ce703d523f447b242f6dfec7582a8c0df102c7fa2849375f8` |
| `OpenAL-Soft-GSL-LICENSE.txt` | the Guidelines Support Library headers OpenAL Soft compiles with | [`gsl/LICENSE` at OpenAL Soft 7f9c6de1162b1d4604b9a2f8bcd6c53021566221](https://raw.githubusercontent.com/kcat/openal-soft/7f9c6de1162b1d4604b9a2f8bcd6c53021566221/gsl/LICENSE) | `4c21dc82a1c7186ea754fd59d504c00947953a3401fe8e24eaa44109ef8e2555` |
| `LGPL-2.1.txt` | JLayer (`javazoom:jlayer`), Java classes whose jar carries no licence | [the Free Software Foundation's text](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt) | `20e50fe7aae3e56378ebf0417d9de904f55a0e61e4df315333e632a4d3555d95` |
