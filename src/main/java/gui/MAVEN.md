# Nanobot GUI 客户端 - Maven 配置说明

## 方式一：直接运行主类

### 1. 编译项目
```bash
mvn clean compile
```

### 2. 运行GUI
```bash
# Windows
mvn exec:java -Dexec.mainClass="gui.NanobotGUI"

# 或使用批处理脚本
启动GUI.bat
```

## 方式二：打包为可执行JAR

### 1. 在 pom.xml 中添加配置

在 `<build><plugins>` 部分添加：

```xml
<!-- 可执行JAR插件 -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-jar-plugin</artifactId>
    <version>3.3.0</version>
    <configuration>
        <archive>
            <manifest>
                <mainClass>gui.NanobotGUI</mainClass>
                <addClasspath>true</addClasspath>
                <classpathPrefix>dependency/</classpathPrefix>
            </manifest>
        </archive>
    </configuration>
</plugin>

<!-- 复制依赖到target/dependency -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-dependency-plugin</artifactId>
    <version>3.6.1</version>
    <executions>
        <execution>
            <id>copy-dependencies</id>
            <phase>package</phase>
            <goals>
                <goal>copy-dependencies</goal>
            </goals>
            <configuration>
                <outputDirectory>${project.build.directory}/dependency</outputDirectory>
            </configuration>
        </execution>
    </executions>
</plugin>

<!-- JavaFX插件（如果需要） -->
<plugin>
    <groupId>org.openjfx</groupId>
    <artifactId>javafx-maven-plugin</artifactId>
    <version>0.0.8</version>
    <configuration>
        <mainClass>gui.NanobotGUI</mainClass>
    </configuration>
</plugin>
```

### 2. 打包
```bash
mvn clean package
```

### 3. 运行
```bash
java -jar target/nanobot-dev-1.0.jar
```

## 方式三：创建Windows可执行文件

### 1. 使用 launch4j

在 `pom.xml` 中添加：

```xml
<plugin>
    <groupId>com.akathist.maven.plugins.launch4j</groupId>
    <artifactId>launch4j-maven-plugin</artifactId>
    <version>2.3.0</version>
    <executions>
        <execution>
            <id>l4j-clui</id>
            <phase>package</phase>
            <goals>
                <goal>launch4j</goal>
            </goals>
            <configuration>
                <headerType>gui</headerType>
                <jar>${project.build.directory}/${project.build.finalName}.jar</jar>
                <outfile>${project.build.directory}/NanobotGUI.exe</outfile>
                <downloadUrl>http://java.com/download</downloadUrl>
                <classPath>
                    <mainClass>gui.NanobotGUI</mainClass>
                    <addDependencies>false</addDependencies>
                    <preCp>dependency/*</preCp>
                </classPath>
                <icon>src/main/resources/icon.ico</icon>
                <jre>
                    <minVersion>17</minVersion>
                    <jdkPreference>preferJre</jdkPreference>
                </jre>
                <versionInfo>
                    <fileVersion>1.0.0.0</fileVersion>
                    <txtFileVersion>1.0.0.0</txtFileVersion>
                    <fileDescription>Nanobot GUI Client</fileDescription>
                    <copyright>2024</copyright>
                    <productVersion>1.0.0.0</productVersion>
                    <txtProductVersion>1.0.0.0</txtProductVersion>
                    <productName>Nanobot GUI</productName>
                    <internalName>NanobotGUI</internalName>
                    <originalFilename>NanobotGUI.exe</originalFilename>
                </versionInfo>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### 2. 打包
```bash
mvn clean package
```

### 3. 运行
双击 `target/NanobotGUI.exe`

## 方式四：使用 JavaPackager

### 1. 创建原生安装包

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-javapackager-plugin</artifactId>
    <version>1.7.0</version>
    <executions>
        <execution>
            <id>package</id>
            <phase>package</phase>
            <goals>
                <goal>package</goal>
            </goals>
            <configuration>
                <mainClass>gui.NanobotGUI</mainClass>
                <name>NanobotGUI</name>
                <platform>windows</platform>
                <createTarball>true</createTarball>
                <bundleJre>true</bundleJre>
                <generateInstaller>true</generateInstaller>
                <administratorRequired>false</administratorRequired>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### 2. 打包
```bash
mvn clean package
```

## 推荐方式

对于开发阶段，推荐使用 **方式一**（直接运行主类）：
- 快速启动
- 无需打包
- 方便调试

对于发布阶段，推荐使用 **方式三**（创建Windows可执行文件）：
- 用户体验好
- 无需手动配置Java环境
- 可自定义图标

## 常见问题

### Q: 运行时提示找不到主类
A: 确保已编译项目：`mvn clean compile`

### Q: 运行时提示找不到依赖
A: 确保依赖已复制到 `target/dependency/` 目录：
```bash
mvn dependency:copy-dependencies -DoutputDirectory=target/dependency
```

### Q: 如何指定JVM参数
A: 在启动命令中添加：
```bash
java -Xmx2g -Xms512m -cp "%CP%" gui.NanobotGUI
```

### Q: 如何调试
A: 添加调试参数：
```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 -cp "%CP%" gui.NanobotGUI
```

## 性能优化

### 减少启动时间

在 `pom.xml` 中添加：

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <compilerArgs>
            <arg>-Xlint:unchecked</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

### 减少内存占用

在启动脚本中添加：

```bash
java -Xmx512m -Xms128m -XX:+UseG1GC -cp "%CP%" gui.NanobotGUI
```

## 下一步

1. 选择合适的打包方式
2. 根据需要修改配置
3. 测试打包结果
4. 发布给用户