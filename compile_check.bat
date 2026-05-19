@echo off
cd /d D:\code\ai_project\javaclawbot
"C:\Program Files\Java\jdk-17\bin\java.exe" -Dmaven.multiModuleProjectDirectory=D:\code\ai_project\javaclawbot -Djansi.passthrough=true -Dmaven.home=D:\IDEA20240307\plugins\maven\lib\maven3 -Dclassworlds.conf=D:\IDEA20240307\plugins\maven\lib\maven3\bin\m2.conf -Dmaven.ext.class.path=D:\IDEA20240307\plugins\maven\lib\maven-event-listener.jar -Dfile.encoding=UTF-8 -classpath D:\IDEA20240307\plugins\maven\lib\maven3\boot\plexus-classworlds-2.8.0.jar org.codehaus.classworlds.Launcher -Dmaven.repo.local=D:\apps\maven\repository compile
