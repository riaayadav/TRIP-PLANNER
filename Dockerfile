FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B verify

FROM tomcat:10.1.59-jre17-temurin
COPY deploy/aiven-ca.pem /opt/roam/aiven-ca.pem
RUN keytool -importcert -noprompt -alias roam-aiven-ca -file /opt/roam/aiven-ca.pem -cacerts -storepass changeit
RUN groupadd --system roam && useradd --system --gid roam roam
COPY --from=build /build/target/trip-planner.war /usr/local/tomcat/webapps/ROOT.war
COPY deploy/server.xml /usr/local/tomcat/conf/server.xml
RUN chown -R roam:roam /usr/local/tomcat
USER roam
EXPOSE 8080
CMD ["catalina.sh", "run"]
