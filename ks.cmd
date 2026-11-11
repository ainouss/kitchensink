@echo off
rem The kitchensink tool: ks start, ks rehearse, ks cutover, ks rollback... (ks help)
java -jar "%~dp0local\target\kitchensink-local-exec.jar" %*
