package com.example.order;

import javax.crypto.Mac;
import javax.naming.Context;

/** Neither javax.crypto nor javax.naming relocated either. */
public class Signer {
    private Mac mac;
    private Context context;
}
