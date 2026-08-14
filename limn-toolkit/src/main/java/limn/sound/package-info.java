/**
 * Backend-neutral audio: an immutable PCM {@link limn.sound.AudioClip}
 * (synthesized via {@link limn.sound.AudioClip#tone} or decoded from WAV/OGG),
 * played through the {@link limn.sound.Sounds} facade over the installed
 * {@link limn.sound.AudioEngine} SPI. Mirrors {@code limn.graphics.Images}:
 * the toolkit stays free of any audio library; the LWJGL backend supplies the
 * OpenAL engine and the file decoder. The system alert beep is just an
 * {@code AudioClip.tone(...)} played through this package.
 */
package limn.sound;
