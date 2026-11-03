#!/usr/bin/env python3
"""Original Field Notes notification tones; deterministic PCM, no external assets."""
import math, pathlib, struct, wave
root=pathlib.Path(__file__).resolve().parents[1]/'apps/mobile/src/androidMain/res/raw'
root.mkdir(parents=True,exist_ok=True)
rate=22050
for tone in ('chime','bell','pulse'):
 for level in (25,50,75,100):
  samples=[]
  for n in range(rate):
   t=n/rate
   if tone=='bell': value=(math.sin(2*math.pi*660*t)+.35*math.sin(2*math.pi*1320*t))*math.exp(-5*t)
   elif tone=='chime':
    value=sum(math.sin(2*math.pi*f*(t-delay))*math.exp(-7*(t-delay)) for f,delay in [(784,0),(988,.22)] if t>=delay)*.7
   else: value=math.sin(2*math.pi*440*t)*sum(math.exp(-30*(t-delay)) for delay in (0,.2,.4) if t>=delay)*.8
   value*=min(1,t/.015)*min(1,(1-t)/.03)*level/100*.65
   samples.append(struct.pack('<h',round(max(-1,min(1,value))*32767)))
  with wave.open(str(root/f'reminder_{tone}_{level}.wav'),'wb') as out:
   out.setparams((1,2,rate,0,'NONE','not compressed'));out.writeframes(b''.join(samples))
