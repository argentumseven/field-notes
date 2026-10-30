// Build with -buildmode=c-shared (Android/Linux/Windows) or c-archive (iOS).
package main

/*
#include <stdlib.h>
*/
import "C"
import (
	"encoding/json"
	"fieldnotes/transport"
	"unsafe"
)

func result(value string, err error) *C.char {
	if err != nil {
		b, _ := json.Marshal(map[string]string{"error": err.Error()})
		return C.CString(string(b))
	}
	return C.CString(value)
}

//export FieldKeys
func FieldKeys() *C.char { v, e := transport.Keys(); return result(v, e) }

//export FieldConfigure
func FieldConfigure(s *C.char) *C.char {
	return result(`{"ok":true}`, transport.Configure(C.GoString(s)))
}

//export FieldExchange
func FieldExchange(s *C.char) *C.char { v, e := transport.Exchange(C.GoString(s)); return result(v, e) }

//export FieldBootstrap
func FieldBootstrap(s *C.char) *C.char {
	v, e := transport.Bootstrap(C.GoString(s))
	return result(v, e)
}

//export FieldStop
func FieldStop() { transport.Stop() }

//export FieldFree
func FieldFree(p *C.char) { C.free(unsafe.Pointer(p)) }
func main()               {}
