import importlib.util
import json
import os
import pathlib
import sys
import unittest
from unittest import mock

ROOT=pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
from container_deployment import Engine,compose,validate,LABEL,ROLE
spec=importlib.util.spec_from_file_location('gateway_rules',ROOT/'deploy/containers/gateway.py')
gateway=importlib.util.module_from_spec(spec);spec.loader.exec_module(gateway)


def settings():
    return dict(version=1,installation='12345678-1234-4234-8234-123456789abc',project='field-notes',
                prefix='/opt/field-notes',data='/var/lib/field-notes',config='/etc/field-notes',
                runtime='/run/field-notes-peers',uid=4242,gid=4242,lan_ip='192.168.1.10',
                lan_prefix='192.168.1.0/24',overlay='10.82.0.0/24',web_port=10443,udp_port=43127,
                images={'app':'sha256:'+'a'*64,'gateway':'sha256:'+'b'*64})


class DockerFixture(Engine):
    def __init__(self,c=None):
        self.settings=c or settings();self.calls=[];self.extra_endpoint=False;self.extra_namespace=False;self.wrong_label=False
        self.ids={'app':'a'*64,'gateway':'b'*64,'network':'c'*64};self.present=set(self.ids);self.tags={'app':True,'gateway':True}
        super().__init__(runner=self.run)
    def run(self,argv,**kwargs):
        args=argv[2:];self.calls.append((args,kwargs))
        if args[:2]==['container','ls']:
            filter_=next((v for v in args if v.startswith(('name=','ancestor='))),None)
            if filter_ and filter_.startswith('name='):
                role='gateway' if filter_.endswith('-gateway$') else 'app'
                output=self.ids[role] if role in self.present else ''
            elif filter_ and filter_.startswith('ancestor='):output=''
            else:output='\n'.join(self.ids[r] for r in ('app','gateway') if r in self.present)
        elif args[:2]==['container','inspect'] and '--format' in args:
            output=self.ids['app']+' container:'+self.ids['gateway']+'\n'+self.ids['gateway']+' bridge'
            if self.extra_namespace:output+='\n'+'d'*64+' container:'+self.ids['gateway']
        elif args[:2]==['container','inspect']:
            role='app' if args[2]==self.ids['app'] else 'gateway'
            output=json.dumps([{'Id':self.ids[role],'Name':'/field-notes-'+role,'Config':{'Labels':{LABEL:'wrong' if self.wrong_label else self.settings['installation'],ROLE:role}},'State':{'Running':True,'Health':{'Status':'healthy'}}}])
        elif args[:2]==['network','ls']:output=self.ids['network'] if 'network' in self.present else ''
        elif args[:2]==['network','inspect']:
            containers={self.ids['gateway']:{}} if 'gateway' in self.present else {}
            if self.extra_endpoint:containers['d'*64]={}
            output=json.dumps([{'Id':self.ids['network'],'Name':'field-notes','Labels':{LABEL:self.settings['installation'],ROLE:'network'},'Containers':containers}])
        elif args[:2]==['container','stop']:output=args[-1]
        elif args[:2]==['container','rm']:
            self.present.discard(next(r for r in self.ids if self.ids[r]==args[-1]));output=''
        elif args[:2]==['network','rm']:self.present.discard('network');output=''
        elif args[:2]==['image','ls']:
            role='gateway' if 'gateway' in args[4] else 'app'
            output=self.settings['images'][role] if self.tags[role] else ''
        elif args[:2]==['image','inspect']:
            role='app' if args[2]==self.settings['images']['app'] else 'gateway'
            output=json.dumps([{'Id':self.settings['images'][role],'Config':{'Labels':{ROLE:role,'org.fieldnotes.runtime':'1'}}}])
        elif args[:2]==['image','rm']:
            self.tags['gateway' if 'gateway' in args[2] else 'app']=False;output=''
        else:raise AssertionError(args)
        return __import__('types').SimpleNamespace(stdout=output,returncode=0)


class Containers(unittest.TestCase):
    def test_spec_has_only_two_ingress_ports_and_no_app_privileges_or_host_secrets(self):
        value=compose(settings());app=value['services']['app'];gateway_=value['services']['gateway']
        self.assertEqual(gateway_['ports'],['192.168.1.10:10443:10443/tcp','0.0.0.0:43127:43127/udp'])
        self.assertEqual(app['network_mode'],'service:gateway');self.assertNotIn('ports',app)
        self.assertEqual(app['cap_drop'],['ALL']);self.assertNotIn('cap_add',app)
        self.assertEqual(gateway_['cap_add'],['NET_ADMIN']);self.assertNotIn('privileged',gateway_)
        self.assertTrue(app['read_only']);self.assertEqual(app['user'],'4242:4242')
        targets={v['target'] for v in app['volumes']}
        self.assertNotIn('/private',targets);self.assertNotIn('/var/run/docker.sock',targets)
        sources={v['source'] for v in app['volumes']}
        self.assertNotIn('/etc/field-notes/tls',sources);self.assertIn('/etc/field-notes/tls/issued',sources)
        self.assertEqual(app['environment']['HTTPS_PROXY'],'');self.assertEqual(gateway_['dns'],['127.0.0.1'])
        self.assertIn('--container-network',app['command']);self.assertIn('10.82.0.1:8443',app['command'])

    def test_invalid_sources_overlay_ports_root_user_and_mutable_images_refuse(self):
        for changes in [{'uid':0},{'lan_prefix':'0.0.0.0/0'},{'overlay':'192.168.1.0/24'}, {'web_port':8443},{'images':{'app':'latest','gateway':'latest'}},{'project':'bungee'}]:
            with self.assertRaises(ValueError):validate({**settings(),**changes})
        mixed={**settings(),'lan_ip':'10.1.1.1','lan_prefix':'10.0.0.0/7','overlay':'192.168.40.0/24'}
        with self.assertRaises(ValueError):validate(mixed)
        with self.assertRaises(ValueError):gateway.rules(mixed)

    def test_firewall_classifies_phone_before_established_and_denies_dns_and_new_output(self):
        text=gateway.rules(settings())
        self.assertLess(text.index('iifname "fieldnotes" counter drop'),text.index('ct state established,related accept'))
        output=text.split('chain output')[1].split('chain forward')[0]
        self.assertLess(output.index('127.0.0.11'),output.index('oifname "lo" accept'))
        self.assertIn('policy drop',output);self.assertIn('counter drop',output)
        self.assertNotIn(' dport 53 accept',text);self.assertIn('policy drop',text.split('chain forward')[1])

    def test_stop_uses_immutable_ids_app_before_gateway_and_never_prunes(self):
        engine=DockerFixture();engine.stop(settings())
        stop=[a[-1] for a,_ in engine.calls if a[:2]==['container','stop']]
        self.assertEqual(stop,[engine.ids['app'],engine.ids['gateway']]);self.assertFalse(engine.present)
        engine.remove_images(settings(),{'app':'field-notes-app:0.3.0','gateway':'field-notes-gateway:0.3.0'})
        self.assertFalse(any(engine.tags.values()))
        self.assertFalse(any('prune' in a or '--force' in a for a,_ in engine.calls))

    def test_wrong_owner_extra_network_member_and_extra_shared_namespace_block_removal(self):
        for flag in ('wrong_label','extra_endpoint','extra_namespace'):
            engine=DockerFixture();setattr(engine,flag,True)
            with self.assertRaises(ValueError):engine.stop(settings())
            self.assertEqual(engine.present,{'app','gateway','network'})
            self.assertFalse(any(a[:2] in (['container','stop'],['container','rm'],['network','rm']) for a,_ in engine.calls))

    def test_local_socket_cannot_be_redirected_and_registry_configuration_is_preserved(self):
        engine=DockerFixture()
        with mock.patch.dict(os.environ,{'DOCKER_HOST':'tcp://unrelated:2375','DOCKER_CONTEXT':'other','DOCKER_CONFIG':'/registry-config'}):
            engine.objects(settings())
        for _,kw in engine.calls:
            self.assertNotIn('DOCKER_HOST',kw['env']);self.assertNotIn('DOCKER_CONTEXT',kw['env'])
            self.assertEqual(kw['env']['DOCKER_CONFIG'],'/registry-config')


if __name__=='__main__':unittest.main()
