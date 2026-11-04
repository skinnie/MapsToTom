# source this: Go 1.23 (last Go that runs on Linux 3.0 kernels, Android 4.0 phones) from the drive
T=/media/skinnie/05a64eef-3fdf-450f-aaf7-09748f80297c/dev-toolchain
export PATH=$T/go1.23/go/bin:$PATH GOTOOLCHAIN=local GOPATH=$T/gopath GOMODCACHE=$T/gopath/pkg/mod GOCACHE=$T/gopath/cache GOFLAGS=-modcacherw
