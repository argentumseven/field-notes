"""Small Unix terminal editor shared by installation and local recovery.

Escape cancels, both terminal Backspace encodings work, and passwords never echo.
Non-terminal plain input is retained for scripted plans; hidden input needs a TTY.
"""
import codecs
import os
import select
import sys
import termios
import tty


class Cancelled(Exception):
    pass


def prompt(label='', secret=False):
    if not sys.stdin.isatty():
        if secret:
            raise ValueError('A terminal is required for hidden password entry')
        print(label, end='', file=sys.stderr, flush=True)
        line=sys.stdin.readline()
        if not line:raise EOFError
        return line.rstrip('\r\n')
    fd=sys.stdin.fileno();original=termios.tcgetattr(fd)
    chars=[];cursor=0;decoder=codecs.getincrementaldecoder('utf-8')('replace')
    print(label, end='', file=sys.stderr, flush=True)
    def redraw():
        if secret:return
        sys.stderr.write('\r\x1b[2K'+label+''.join(chars))
        if cursor<len(chars):sys.stderr.write('\x1b['+str(len(chars)-cursor)+'D')
        sys.stderr.flush()
    try:
        tty.setraw(fd)
        while True:
            byte=os.read(fd,1)
            if byte in (b'\r',b'\n'):return ''.join(chars)
            if byte in (b'',b'\x03',b'\x04'):raise Cancelled()
            if byte==b'\x1b':
                # A bare Escape cancels; consume complete navigation sequences
                # so arrow/Delete keys never become characters in an address.
                sequence=b''
                while len(sequence)<16 and select.select([fd],[],[],0.08)[0]:
                    sequence+=os.read(fd,1)
                    if len(sequence)>1 and (65<=sequence[-1]<=90 or 97<=sequence[-1]<=122 or sequence[-1]==126):break
                if not sequence:raise Cancelled()
                if sequence in (b'[D',b'OD'):cursor=max(0,cursor-1)
                elif sequence in (b'[C',b'OC'):cursor=min(len(chars),cursor+1)
                elif sequence in (b'[H',b'OH',b'[1~',b'[7~'):cursor=0
                elif sequence in (b'[F',b'OF',b'[4~',b'[8~'):cursor=len(chars)
                elif sequence==b'[3~' and cursor<len(chars):chars.pop(cursor)
                redraw();continue
            if byte in (b'\x08',b'\x7f'):
                if cursor:cursor-=1;chars.pop(cursor)
                decoder.reset();redraw();continue
            if byte==b'\x15':chars.clear();cursor=0;decoder.reset();redraw();continue
            if byte[0]<32:continue
            text=decoder.decode(byte)
            if text:
                chars[cursor:cursor]=text;cursor+=len(text);redraw()
    finally:
        termios.tcsetattr(fd,termios.TCSADRAIN,original)
        print(file=sys.stderr,flush=True)
