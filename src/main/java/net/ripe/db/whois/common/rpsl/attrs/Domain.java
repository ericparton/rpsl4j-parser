package net.ripe.db.whois.common.rpsl.attrs;

import net.ripe.db.whois.common.domain.CIString;
import net.ripe.db.whois.common.ip.IpInterval;
import net.ripe.db.whois.common.ip.Ipv4Resource;
import net.ripe.db.whois.common.ip.Ipv6Resource;

import javax.annotation.CheckForNull;
import javax.annotation.concurrent.Immutable;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.ripe.db.whois.common.domain.CIString.ciString;

@Immutable
public class Domain {
    private static final Pattern E164_SYNTAX = Pattern.compile("(?i)^(?:[0-9]\\.)+e164.arpa$");
    private static final int DOMAIN_COLUMN_WIDTH = 254;

    private final CIString value;
    private final IpInterval<?> reverseIp;
    private final Type type;
    private final boolean isDashNotation;

    public Domain(final CIString value, final IpInterval<?> reverseIp, final Type type, final boolean dashNotation) {
        this.value = value;
        this.reverseIp = reverseIp;
        this.type = type;
        this.isDashNotation = dashNotation;
    }

    @Override
    public String toString() { //TODO: no tests written yet. Edit: reverseIP apparently not set for ipv6 addresses. Updated..
    	if(type.equals(Type.E164))
    		return value.toString() + "(" + type.toString() + " " + (isDashNotation ? "dashed" : "not-dashed") + ")";
    	else
    		return value.toString() + "(" + reverseIp.toString() + " " + type.toString() + " " + (isDashNotation ? "dashed" : "not-dashed") + ")";
    }
    
    @Override
    public boolean equals(final Object o) { //TODO: write tests for this
    	if(o == this)
    		return true;
    	if(o == null || !(o instanceof Domain))
    		return false;
    	else {
    		final Domain that = (Domain) o;
    		//TODO: DEBUGGING
    		//System.out.println("Null list for entity'" + value + "':" + (value==null?"value ":" ")+(reverseIp==null?"reverseIp ":" ")+(type==null?"type ":" "));
    		
    		//in the case of ipv6 addresses, reverseIp will not be available.. it seems
    		//if(reverseIp==null)
    		if(type.equals(Type.E164)) //if E164, reverseIP will not apply
    			return value.equals(that.value) && type.equals(that.type) && isDashNotation==that.isDashNotation;
    		else
    			return value.equals(that.value) && reverseIp.equals(that.reverseIp) && type.equals(that.type) && isDashNotation==that.isDashNotation;
    		//note that CIString is case insensitive for equals(). This is probably a good thing here, given dns names are also case insensitive.. at least to cut a long story short..
    	}
    }
    
    @Override
    public int hashCode() { //TODO: no tests yet 
    	return toString().hashCode();
    }
    
    public CIString getValue() {
        return value;
    }

    public Type getType() {
        return type;
    }

    @CheckForNull
    public IpInterval<?> getReverseIp() {
        return reverseIp;
    }

    public boolean endsWithDomain(final CIString hostname) {
        if (!isDashNotation || !(reverseIp instanceof Ipv4Resource)) {
            return hostname.endsWith(value);
        }

        final String domainValue = value.toString();
        final Pattern hostnameCheckPattern = Pattern.compile("(?i)(?:.*)(\\d+)" + Pattern.quote(domainValue.substring(domainValue.indexOf('.'))));
        final Matcher hostnameMatcher = hostnameCheckPattern.matcher(hostname.toString());
        if (!hostnameMatcher.matches()) {
            return false;
        }

        final Ipv4Resource reverseIpv4 = (Ipv4Resource) reverseIp;
        final int last = Integer.parseInt(hostnameMatcher.group(1));

        return !(((reverseIpv4.begin() & 0xff) > last) || ((reverseIpv4.end() & 0xff) < last));
    }

    public static Domain parse(final CIString value) {
        return parse(value.toString());
    }

    public static Domain parse(final String domain) {
        String value = domain;
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }

        if (value.length() > DOMAIN_COLUMN_WIDTH) {
            throw new AttributeParseException("Too long", value);
        }
        final int lastDot = value.lastIndexOf('.');
        final int secondLastDot = value.lastIndexOf('.', lastDot - 1);
        final String suffix = value.substring(secondLastDot + 1);
        final Type type = Type.getType(suffix);
        if (type == null) {
            throw new AttributeParseException(String.format("Unknown suffix '%s'", suffix), value);
        }

        IpInterval<?> reverseIp = null;
        boolean dashNotation = false;
        try {
            switch (type) {
                case INADDR:
                    final Ipv4Resource ipv4Resource = Ipv4Resource.parseReverseDomain(value);
                    dashNotation = checkIpv4Domain(value, ipv4Resource);
                    reverseIp = ipv4Resource;
                    break;
                case IP6:
                    reverseIp = Ipv6Resource.parseReverseDomain(value);
                    break;
                case E164:
                    if (!E164_SYNTAX.matcher(value).matches()) {
                        throw new AttributeParseException("Unknown e164.arpa value", value);
                    }
                    break;
            }
        } catch (IllegalArgumentException e) {
            throw new AttributeParseException(e.getMessage(), value);
        }

        return new Domain(ciString(value), reverseIp, type, dashNotation);
    }

    private static boolean checkIpv4Domain(final String value, final Ipv4Resource ipv4Resource) {
        final long rangeLength = ipv4Resource.end() - ipv4Resource.begin();

        final int firstDash = value.indexOf('-');
        if (firstDash > 0) {
            final int firstDot = value.indexOf('.');
            if (firstDash < firstDot) { // has dash notation
                if (rangeLength == 255) {
                    throw new AttributeParseException("Invalid use of dash notation", value);
                }
                return true;
            }
        }
        return false;
    }

    public static enum Type {
        INADDR("in-addr.arpa"),
        IP6("ip6.arpa"),
        E164("e164.arpa");

        private static final Map<String, Type> nameToType = new HashMap<>();

        static {
            for (Type type : Type.values()) {
                nameToType.put(type.getSuffix(), type);
            }
        }

        private String suffix;

        Type(final String suffix) {
            this.suffix = suffix;
        }

        public String getSuffix() {
            return suffix;
        }

        @CheckForNull
        public static Type getType(final String suffix) {
            return nameToType.get(suffix.toLowerCase());
        }
    }
}
