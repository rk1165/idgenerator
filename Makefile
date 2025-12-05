run:
	./gradlew bootRun

debug:
	./gradlew bootRun --debug-jvm

build:
	./gradlew clean build

clean:
	./gradlew clean

PHONY: run debug build clean