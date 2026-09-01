START RequestId: 5b3aa800-5ff2-4756-89f4-8b01418f8b79 Version: $LATEST
Error loading class com.adcb.cert.S3FileLambda: software/amazon/awssdk/core/SdkClient: java.lang.NoClassDefFoundError
java.lang.NoClassDefFoundError: software/amazon/awssdk/core/SdkClient
	at java.base/java.lang.ClassLoader.defineClass1(Native Method)
	at java.base/java.lang.ClassLoader.defineClass(Unknown Source)
	at java.base/java.security.SecureClassLoader.defineClass(Unknown Source)
	at java.base/java.net.URLClassLoader.defineClass(Unknown Source)
	at java.base/java.net.URLClassLoader$1.run(Unknown Source)
	at java.base/java.net.URLClassLoader$1.run(Unknown Source)
	at java.base/java.security.AccessController.doPrivileged(Unknown Source)
	at java.base/java.net.URLClassLoader.findClass(Unknown Source)
	at java.base/java.lang.ClassLoader.loadClass(Unknown Source)
	at java.base/java.lang.ClassLoader.loadClass(Unknown Source)
	at com.adcb.cert.S3FileLambda.<clinit>(S3FileLambda.java:16)
	at java.base/java.lang.Class.forName0(Native Method)
	at java.base/java.lang.Class.forName(Unknown Source)
	at java.base/java.lang.Class.forName(Unknown Source)
Caused by: java.lang.ClassNotFoundException: software.amazon.awssdk.core.SdkClient
	at java.base/java.net.URLClassLoader.findClass(Unknown Source)
	at java.base/java.lang.ClassLoader.loadClass(Unknown Source)
	at java.base/java.lang.ClassLoader.loadClass(Unknown Source)
	... 14 more

END RequestId: 5b3aa800-5ff2-4756-89f4-8b01418f8b79
REPORT RequestId: 5b3aa800-5ff2-4756-89f4-8b01418f8b79	Duration: 1248.31 ms	Billed Duration: 1542 ms	Memory Size: 128 MB	Max Memory Used: 94 MB	Init Duration: 293.05 ms	Status: error	Error Type: Runtime.ExitError
