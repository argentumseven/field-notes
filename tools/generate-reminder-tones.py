#!/usr/bin/env python3
"""Original deterministic PCM: brief notification cues and distinct sustained alarms."""
import math
import pathlib
import struct
import wave

ROOT = pathlib.Path(__file__).resolve().parents[1] / 'apps/mobile/src/androidMain/res/raw'
TONES = ('chime', 'bell', 'pulse', 'beacon', 'marimba', 'orbit', 'sunrise', 'siren')
RATE = 22050


def sample(tone, t, alarm):
    if alarm:
        # Alarm phrases repeat audibly; these are not stretched notification cues.
        beat = t % 1.5
        if tone == 'siren':
            return math.sin(2*math.pi*620*t-110*math.cos(2*math.pi*.8*t))*.7
        if tone == 'sunrise':
            return sum(math.sin(2*math.pi*f*t) for f in (262,330,392))/3*(.35+.45*math.sin(math.pi*t/1.5)**2)
        if tone == 'orbit':
            return (math.sin(2*math.pi*(330*t+8*math.sin(math.pi*t)))+.25*math.sin(2*math.pi*660*t))*(.35+.35*math.sin(2*math.pi*t)**2)
        if tone == 'bell':
            return (math.sin(2*math.pi*660*beat)+.3*math.sin(2*math.pi*1320*beat))*math.exp(-1.5*beat)*.65
        if tone == 'marimba':
            beat=t%.375
            f=(523,659,784,1046)[int(t/.375)%4]
            return math.sin(2*math.pi*f*beat)*math.exp(-5*beat)*.75
        freq=880 if tone=='chime' else 660 if tone=='pulse' else 554
        return sum(math.sin(2*math.pi*f*(beat-delay))*math.exp(-4*(beat-delay)) for f,delay in ((freq,0),(freq*1.5,.4),(freq,.8)) if beat>=delay)*.65
    if tone == 'bell':
        return (math.sin(2*math.pi*660*t)+.35*math.sin(2*math.pi*1320*t))*math.exp(-7*t)
    if tone == 'pulse':
        return math.sin(2*math.pi*440*t)*sum(math.exp(-35*(t-delay)) for delay in (0,.16,.32) if t>=delay)*.8
    if tone == 'marimba':
        return sum(math.sin(2*math.pi*f*(t-delay))*math.exp(-12*(t-delay)) for f,delay in ((523,0),(659,.16),(784,.32)) if t>=delay)*.7
    # Saved legacy names still play a brief cue in notification mode.
    frequencies={'chime':(784,988),'beacon':(554,830),'orbit':(330,660),'sunrise':(262,392),'siren':(600,800)}
    return sum(math.sin(2*math.pi*f*(t-delay))*math.exp(-8*(t-delay)) for f,delay in zip(frequencies[tone],(0,.2)) if t>=delay)*.7


def generate():
    ROOT.mkdir(parents=True, exist_ok=True)
    for alarm in (False, True):
        for tone in TONES:
            duration = (6 if tone=='sunrise' else 4) if alarm else (.65 if tone=='pulse' else .9)
            for level in (25,50,75,100):
                pcm=bytearray()
                for n in range(round(RATE*duration)):
                    t=n/RATE
                    value=sample(tone,t,alarm)*min(1,t/.015)*min(1,(duration-t)/.03)*level/100*.65
                    pcm.extend(struct.pack('<h',round(max(-1,min(1,value))*32767)))
                prefix='alarm' if alarm else 'reminder'
                with wave.open(str(ROOT/f'{prefix}_{tone}_{level}.wav'),'wb') as out:
                    out.setparams((1,2,RATE,0,'NONE','not compressed'))
                    out.writeframes(pcm)


if __name__ == '__main__':
    generate()
