# TWCtestv3

This program is partially based upon the TWCmanager written in Python which you can find here on Github.

But this is entirely written in JAVA and can run on any Java 1.8, including a raspberry pi zero.
It uses only 1 external library: com.fazecast:JserialComm:2.9.0. This library does the RS485 communication and has a broad compatibility including arm v6 and v7 cpu.

Basically the program has 3 fonctions:
1. a background thread runs a super-small webserver so you can view the status
2. a second thread communicates with an SMA Sunny home manager or SMA energy meter to find out the power consumption from the grid
3. the main thread uses a RS485 port to show itself as as master to a Tesla wall charger (generation 2).

You can set the maximum power drawn from the grid.
The program will instruct the Tesla wall charger (set as slave) so that the power consumption from the grid never exceeds this set value.
This will work even with additional injection by solar panels or home batteries.
