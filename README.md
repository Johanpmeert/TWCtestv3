# TWCtestv3

This program is partially based upon the TWCmanager written in Python which you can find here on Github.

But this is entirely written in JAVA and can run on any Java 1.8, including a raspberry pi zero.
It uses only 1 external library: com.fazecast:JserialComm:2.9.0. This library does the RS485 communication and has a broad compatibility including arm v6 and v7 cpu.

Basically the program has 3 functions:
1. A background thread runs a super-small webserver so you can view the status remotely (port 8085)
2. A second thread communicates with an SMA Sunny home manager or SMA energy meter to find out the power consumption from the grid
3. The main thread uses an RS485 port to show itself as as master to a Tesla wall charger (generation 2) and send commands to the slave

You can set the maximum power drawn from the grid.
The program will instruct the Tesla wall charger (set as slave) so that the power consumption from the grid never exceeds this set value.
This will work even with additional injection by solar panels or home batteries. It uses 30 second intervals to egulate the power draw.

A log file (TWC.log) is created and contains any and all communication with the TWC. The same output is also given on the console.

I wrote this program to cope with the new 2022 Belgian regulation that will partially bill electricity according to the highest power draw in a 15 minute window every month.
Since chartging an electric car is by far the highest power draw, regulating this charge will allow:
- the car to be charged (slowly) 24/7 without any limitations needing to be set in the car/app.
- the maximum power draw to be known in advance
Experimenting with the setting of the aximum power draw from the grid will allow to minimize the cost while being sure that the can be fully charged.
