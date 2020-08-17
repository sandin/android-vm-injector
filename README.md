# Android Injector v0.0.4

## 简介

本工具提供了Android应用运行时注入.so文件并拉起的功能，支持root机注入release、debuggable应用及非root机注入debuggable应用

## 功能 Features

1. 检测Android应用的架构
2. Android应用运行时注入.so文件并拉起

## 安装 Install

### with-jre

1. 下载zip包
2. 解压缩

### without-jre

1. 配置本地Java环境
2. 下载without-jre.zip
3. 解压缩

## 使用 Usage

### .so注入 
```
$ artinjector -i <injecto_so> -p <package_name>
```
可选参数:

- [ --adbPath <adb_path>]

  - 指定adb路径
  
如果未指定adb路径则优先寻找系统中已存在的adb进程和环境变量中的adb路径
非root手机仅支持注入debuggable的Android应用

### 检测app架构(32/64 bit)
```
$ artinjector -p <package_name> -a
```



### 启动应用并置于等待调试器阶段

```
$ artinjector -p <package_name> --launch
```

可选参数:

- [-ac  <activity_name>] 根据输入的activity_name来启动应用，如果未指定activity_name则根据package_name启动应用

### 错误码 ErrorCodes

```markdown
SOFILE_NOT_EXIST = 1 		    		//.so文件不存在
CANT_FIND_DEVICE = 2;		    		//无法找到设备
CANT_GET_CLIENT = 3;		    		//选择的应用不是debuggable或者未启动
CANT_PUSH_FILE = 4;	        		//无法将.so推入手机
CANT_ATTACH_APP = 5;		    		//无法连接应用
BREAKPOINT_TIMEOUT = 6; 	  		//断点超时
SOFILE_SHOULD_USE_32BIT = 7; 		//应用为32位，使用了64位的.so
SOFILE_SHOULD_USE_64BIT = 8;    //应用为64位，使用了32位的.so
LOAD_SO_FAIL = 9;               //拉起so失败
CANT_GET_ADB = 10;               //无法创建调试桥
```

## 常见问题 Tips
* 如果一直在等待断点阶段(wait breakpoints)，尝试切出应用再切回应用
* 无法创建调试桥的可能原因：没有将本地的adb配置到环境变量,未手动指定本地的adb路径及未启动adb服务，解决方案：
  1. 将本地的adb添加到环境变量
  2. 使用--adbPath <adb_path>来指定adb路径
  3. 启动adb服务
  
