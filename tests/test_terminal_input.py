import json,os,pathlib,pty,select,subprocess,sys,termios,time,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]

class TerminalInput(unittest.TestCase):
 def run_prompt(self,keys,secret=False):
  master,slave=pty.openpty();original=termios.tcgetattr(slave)
  code="import json,sys;sys.path.insert(0,'scripts');from terminal_input import prompt,Cancelled\ntry:\n value=prompt('Value: ',secret="+str(secret)+");print('RESULT:'+json.dumps(len(value) if "+str(secret)+" else value))\nexcept Cancelled:print('CANCELLED');sys.exit(130)"
  child=subprocess.Popen([sys.executable,'-c',code],cwd=ROOT,stdin=slave,stdout=slave,stderr=slave)
  output=b''
  try:
   deadline=time.monotonic()+5
   while time.monotonic()<deadline:
    if select.select([master],[],[],.1)[0]:output+=os.read(master,4096)
    if b'Value: ' in output and not termios.tcgetattr(slave)[3]&termios.ECHO:break
   else:self.fail('Prompt did not enter terminal editing mode')
   os.write(master,keys)
   while child.poll() is None and time.monotonic()<deadline:
    if select.select([master],[],[],.1)[0]:output+=os.read(master,4096)
   child.wait(timeout=2)
   while select.select([master],[],[],.05)[0]:output+=os.read(master,4096)
   self.assertEqual(termios.tcgetattr(slave),original,'terminal settings restored')
   return child.returncode,output.decode()
  finally:
   if child.poll() is None:child.kill();child.wait()
   os.close(master);os.close(slave)
 def test_backspace_both_encodings_and_literal_exclamation(self):
  code,out=self.run_prompt(b'wrong\x15admiX\x08nZ\x7f!\r')
  self.assertEqual(code,0);self.assertIn('RESULT:"admin!"',out)
 def test_navigation_delete_and_utf8_do_not_insert_escape_sequences(self):
  code,out=self.run_prompt('café'.encode()+b'\x1b[D\x1b[3~e\r')
  self.assertEqual(code,0);self.assertIn('RESULT:"cafe"',out)
 def test_hidden_password_edits_without_echo(self):
  code,out=self.run_prompt(b'private-passwordX\x08!\r',secret=True)
  self.assertEqual(code,0);self.assertIn('RESULT:17',out);self.assertNotIn('private',out)
 def test_escape_cancels_and_restores_terminal(self):
  code,out=self.run_prompt(b'abandoned\x1b')
  self.assertEqual(code,130);self.assertIn('CANCELLED',out);self.assertNotIn('RESULT:',out)

if __name__=='__main__':unittest.main()
