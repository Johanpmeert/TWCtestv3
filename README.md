# TWCtestv3

This program is partially based upon the TWCmanager written in Python which you can find here on Github.

But this is entirely written in JAVA and can run on any Java 1.8, including a raspberry pi zero.
It uses only 1 external library: com.fazecast:JserialComm:2.9.1. This library does the RS485 communication and has a broad compatibility including arm v6 and v7 cpu.

Basically the program has 3 functions:
1. A background thread runs a super-small webserver so you can view the status remotely (standard on port 8085)
2. A second thread communicates with an SMA Sunny home manager or SMA energy meter to find out the power consumption from the grid
3. The main thread uses an RS485 port to show itself as as master to a Tesla wall charger (generation 2) and send commands to the slave

You can stop the program by calling the website like xx.xx.xx.xx:8085/endprogram.

Logging can be started or stopped by using /loggingoff and /loggingon

You can set the maximum power drawn from the grid.
The program will instruct the Tesla wall charger (set as slave) so that the power consumption from the grid never exceeds this set value.
This will work even with additional injection by solar panels or home batteries. It uses 30 second intervals to regulate the power draw.

From experience you do not want to wait for responses from the TWC slave before sending a command. Otherwise there will be timeouts and the charger will stop/restart the charging process every few minutes. The most important thing is a steady stream of commands. Once every minute a command to re-set the charging amps, all the other can be command 0 (no change).

A log file (TWC.log) is created and contains any and all communication with the TWC. The same output is also given on the console.

I wrote this program to cope with the new 2022 Belgian regulation that will partially bill electricity according to the highest power draw in a 15 minute window every month.
Since charging an electric car is by far the highest power draw, regulating this charge will allow:
- the car to be charged (slowly) 24/7 without any limitations needing to be set in the car/app
- the maximum power draw to be known in advance

Experimenting with the setting of the maximum power draw from the grid will allow to minimize the cost while being sure that the car can be fully charged.

My hardware:
- raspberry pi zero W, with java installed with command 'sudo apt install openjdk-8-jre-zero', with an added HAT to provide a RJ45 network connection
- RS485 adapter from amazon, arceli ftdi ft232rl usb/ttl/rs485

Contents of a typical config.txt file (one will be created if it does not exist):
```
http_port = 8085
max_power_from_mains = 10000.0
master_id = 7777
rs485_port = ttyUSB0
sma_serial = 3004908651
```
