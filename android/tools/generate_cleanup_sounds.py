"""Original PCM sound effects, generated offline with the Python standard library."""
from pathlib import Path
import math
import struct
import wave

RATE = 22050
OUT = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw'
OUT.mkdir(parents=True, exist_ok=True)

def write(name, samples):
    with wave.open(str(OUT / (name + '.wav')), 'wb') as wav:
        wav.setparams((1, 2, RATE, 0, 'NONE', 'not compressed'))
        wav.writeframes(b''.join(struct.pack('<h', round(max(-1, min(1, v)) * 32767)) for v in samples))

complete = []
for i in range(round(.38 * RATE)):
    t = i / RATE
    sample = 0.0
    for start, frequency, length in [(0, 660, .22), (.11, 990, .27)]:
        p = t - start
        if 0 <= p < length:
            envelope = min(1, p / .012) * (1 - p / length) ** 2
            sample += .22 * envelope * (math.sin(2 * math.pi * frequency * p) + .16 * math.sin(4 * math.pi * frequency * p))
    complete.append(sample)
write('cleanup_complete', complete)
