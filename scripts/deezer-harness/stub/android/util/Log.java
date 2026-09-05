package android.util;
import java.util.*;
public class Log {
    public static final List<String> LINES = Collections.synchronizedList(new ArrayList<>());
    public static volatile boolean echo = false;
    private static int p(String l,String t,String m){ String s=l+"/"+t+": "+m; LINES.add(s); if(echo) System.out.println("      "+s); return 0; }
    public static int i(String t,String m){ return p("I",t,m); }
    public static int d(String t,String m){ return p("D",t,m); }
    public static int w(String t,String m){ return p("W",t,m); }
    public static int e(String t,String m){ return p("E",t,m); }
    public static int e(String t,String m,Throwable x){ return p("E",t,m+" "+x); }
    public static int w(String t,String m,Throwable x){ return p("W",t,m+" "+x); }
    public static boolean saw(String n){ synchronized(LINES){ for(String s:LINES) if(s.contains(n)) return true; } return false; }
    public static int count(String n){ int c=0; synchronized(LINES){ for(String s:LINES) if(s.contains(n)) c++; } return c; }
    public static void clear(){ LINES.clear(); }
}
