#!/usr/bin/env python3
"""Original Field Notes notification tones; deterministic PCM, no external assets."""
import math, pathlib, struct, wave
root=pathlib.Path(__file__).resolve().parents[1]/'apps/mobile/src/androidMain/res/raw'
root.mkdir(parents=True,exist_ok=True)
rate=22050
for tone in ('chime','bell','pulse','beacon','marimba','orbit','sunrise','siren'):
 for level in (25,50,75,100):
  duration={'beacon':4,'marimba':4,'orbit':6,'sunrise':6,'siren':8}.get(tone,1)
  samples=[]
  for n in range(rate*duration):
   t=n/rate
   if tone=='bell': value=(math.sin(2*math.pi*660*t)+.35*math.sin(2*math.pi*1320*t))*math.exp(-5*t)
   elif tone=='chime':
    value=sum(math.sin(2*math.pi*f*(t-delay))*math.exp(-7*(t-delay)) for f,delay in [(784,0),(988,.22)] if t>=delay)*.7
   elif tone=='pulse': value=math.sin(2*math.pi*440*t)*sum(math.exp(-30*(t-delay)) for delay in (0,.2,.4) if t>=delay)*.8
   elif tone=='beacon':
    beat=t%1.25;value=sum(math.sin(2*math.pi*f*(beat-delay))*math.exp(-6*(beat-delay)) for f,delay in ((554,0),(830,.38)) if beat>=delay)*.65
   elif tone=='marimba':
    beat=t%.5;f=(523,659,784,1046,784,659,523,392)[int(t/.5)%8];value=(math.sin(2*math.pi*f*beat)+.15*math.sin(2*math.pi*f*3*beat))*math.exp(-9*beat)*.7
   elif tone=='orbit': value=(math.sin(2*math.pi*(330*t+12*math.sin(2*math.pi*.35*t)))+.25*math.sin(2*math.pi*660*t))*(.4+.3*math.sin(2*math.pi*.8*t)**2)
   elif tone=='sunrise': value=sum(math.sin(2*math.pi*f*t) for f in (262,330,392))/3*(.4+.4*math.sin(2*math.pi*.25*t)**2)
   else: value=math.sin(2*math.pi*600*t-500*math.cos(2*math.pi*.6*t))*(.55+.25*math.sin(2*math.pi*2*t)**2)
   value*=min(1,t/.015)*min(1,(duration-t)/.03)*level/100*.65
   samples.append(struct.pack('<h',round(max(-1,min(1,value))*32767)))
  with wave.open(str(root/f'reminder_{tone}_{level}.wav'),'wb') as out:
   out.setparams((1,2,rate,0,'NONE','not compressed'));out.writeframes(b''.join(samples))
