# Android Injector

## Usage

```
$ artinjector -i <injecto_so> -p <package_name>
```

## ErrorCodes

```
SOFILE_NOT_EXIST = 1 		    //.so文件不存在
CANT_FIND_DEVICE = 2;		    //无法找到设备
CANT_GET_CLIENT = 3;		    //选择的应用不是debuggable或者未启动
CANT_PUSH_FILE = 4;			    //无法将.so推入手机
CANT_ATTACH_APP = 5;		    //无法连接应用
BREAKPOINT_TIMEOUT = 6; 	    //断点超时
SOFILE_SHOULD_USE_32BIT = 7;    //应用为32位，使用了64位的.so
SOFILE_SHOULD_USE_64BIT = 8;    //应用为64位，使用了32位的.so
```