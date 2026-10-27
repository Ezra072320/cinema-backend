# TC 2.5 ships without a JDBC driver. Match the application's managed Connector/J version.
FROM apache/seata-server:2.5.0
ADD --checksum=sha256:49ed93c8b2bea9cb0929b85a8a28837b191d0f8eac6919fdcef16e36e2cd53b3 https://repo.maven.apache.org/maven2/com/mysql/mysql-connector-j/9.4.0/mysql-connector-j-9.4.0.jar /seata-server/libs/jdbc/mysql-connector-j.jar
