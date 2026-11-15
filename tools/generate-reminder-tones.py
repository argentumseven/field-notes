#!/usr/bin/env python3
"""Original deterministic PCM: brief notification cues and distinct sustained alarms."""
import math
import pathlib
import struct
import wave
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1] / 'apps/mobile/src/androidMain/res/raw'
TONES = ('chime', 'bell', 'pulse', 'beacon', 'marimba', 'orbit', 'sunrise', 'siren','signal','crystal','radar','klaxon')
LEGACY = pathlib.Path(__file__).resolve().parents[1] / 'apps/server/assets/sounds/v1'
RATE = 22050
# Each alarm clip spans a whole number of its own phrase periods (1.5 s beats, a 1.25 s siren sweep,
# a 2 s orbit drift), so it can loop without a seam. Notification cues are one-shots.
ALARM_SECONDS = {'sunrise': 6, 'siren': 5, 'orbit': 4}


def ring(freq, age, decay):
    """An exponentially decaying sine that has not started yet when age < 0."""
    return math.sin(2*math.pi*freq*age)*math.exp(-decay*age) if age >= 0 else 0.0


def sample(tone, t, alarm):
    if tone=='signal':
        return sum(ring(f,t-delay,6 if not alarm else 2) for f,delay in ((740,0),(988,.18),(1480,.36)))*.65 if not alarm else sum(ring(f,t%1.5+1.5*k-delay,3) for k in (0,1,2) for f,delay in ((740,0),(988,.3),(1480,.6)))*.65
    if tone=='crystal':
        beat=t%1.5 if alarm else t
        return sum(ring(1046*n,beat+1.5*k,.9+n*2)/n**1.5 for k in ((0,1,2) if alarm else (0,)) for n in range(1,5))
    if tone=='radar':
        beat=t%1.5 if alarm else t
        return sum(ring(f,beat+1.5*k-d,7) for k in ((0,1,2) if alarm else (0,)) for f,d in ((880,0),(1174,.2),(1568,.4)))
    if tone=='klaxon':
        return (math.sin(2*math.pi*440*t)+.45*math.sin(2*math.pi*660*t)+.2*math.sin(2*math.pi*880*t))*(.4+.6*math.sin(2*math.pi*2*t)**2) if alarm else ring(880,t,6)+.5*ring(1320,t-.2,8)
    if alarm:
        # Alarm phrases loop for the whole ring time, so every phrase is exactly periodic over its
        # clip: the tail of the previous beat is folded into the next one, which makes the loop seam
        # continuous without any baked fade. See ALARM_SECONDS for the matching clip lengths.
        if tone == 'siren':
            return math.sin(2*math.pi*620*t-110*math.cos(2*math.pi*.8*t))*.7
        if tone == 'sunrise':
            return sum(math.sin(2*math.pi*f*t) for f in (262,330,392))/3*(.35+.45*math.sin(math.pi*t/1.5)**2)
        if tone == 'orbit':
            return (math.sin(2*math.pi*(330*t+8*math.sin(math.pi*t)))+.25*math.sin(2*math.pi*660*t))*(.35+.35*math.sin(2*math.pi*t)**2)
        if tone == 'bell':
            beat=t%1.5
            return sum((math.sin(2*math.pi*660*(beat+1.5*k))+.3*math.sin(2*math.pi*1320*(beat+1.5*k)))*math.exp(-1.5*(beat+1.5*k)) for k in (0,1,2))*.65
        if tone == 'marimba':
            beat=t%.375;index=int(t/.375)
            return sum(ring((523,659,784,1046)[(index-k)%4],beat+.375*k,5) for k in (0,1,2))*.75
        beat=t%1.5
        freq=880 if tone=='chime' else 660 if tone=='pulse' else 554
        return sum(ring(f,beat+1.5*k-delay,4) for k in (0,1) for f,delay in ((freq,0),(freq*1.5,.4),(freq,.8)))*.65
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
    """Requires ffmpeg only when regenerating committed, original audio assets."""
    ROOT.mkdir(parents=True, exist_ok=True)
    LEGACY.mkdir(parents=True, exist_ok=True)
    verified = 0
    with tempfile.TemporaryDirectory(prefix='field-notes-audio-') as directory:
        temporary = pathlib.Path(directory)/'source.wav'
        for alarm in (False, True):
            for tone in TONES:
                duration = ALARM_SECONDS.get(tone, 3) if alarm else (.65 if tone=='pulse' else .9)
                samples=[sample(tone,n/RATE,alarm) for n in range(round(RATE*duration))]
                rms=math.sqrt(sum(v*v for v in samples)/len(samples)) or 1
                normal=(.26 if alarm else .23)/rms
                for level in (25,50,75,100,125,150,200):
                    pcm=bytearray();old=bytearray()
                    for n in range(round(RATE*duration)):
                        t=n/RATE
                        edges=1 if alarm else min(1,t/.015)*min(1,(duration-t)/.03)
                        value=math.tanh(samples[n]*normal*edges*level/100)*.98
                        pcm.extend(struct.pack('<h',round(value*32767)))
                        previous=samples[n]*edges*level/100*.65
                        old.extend(struct.pack('<h',round(max(-1,min(1,previous))*32767)))
                    prefix='alarm' if alarm else 'reminder'
                    name=f'{prefix}_{tone}_{level}'
                    def wav(path,data):
                        with wave.open(str(path),'wb') as out:
                            out.setparams((1,2,RATE,0,'NONE','not compressed'));out.writeframes(data)
                    wav(temporary,pcm)
                    target=ROOT/(name+'.flac')
                    subprocess.run(['ffmpeg','-v','error','-y','-i',str(temporary),'-map_metadata','-1','-fflags','+bitexact','-flags:a','+bitexact','-compression_level','12','-threads','1',str(target)],check=True)
                    decoded=subprocess.check_output(['ffmpeg','-v','error','-i',str(target),'-f','s16le','-acodec','pcm_s16le','-threads','1','-'])
                    assert decoded==pcm,name
                    values=struct.unpack('<'+'h'*(len(pcm)//2),pcm)
                    if alarm:
                        step=max(abs(a-b) for a,b in zip(values,values[1:]))
                        assert abs(values[0]-values[-1])<=step,name
                    if tone in TONES[:8] and level<=100:wav(LEGACY/(name+'.wav'),old)
                    verified+=1
    print(f'Verified {verified} lossless clips against every generated PCM sample.')

if __name__ == '__main__':
    generate()
