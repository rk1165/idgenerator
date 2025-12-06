PORT ?=8080

run:
	./gradlew bootRun -Dserver.port=$(PORT)

debug:
	./gradlew bootRun --debug-jvm

build:
	./gradlew clean build

clean:
	./gradlew clean

jmh:
	./gradlew jmh

PHONY: run debug build clean jmh