package lombok;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.SOURCE)
public @interface PartialFieldsResolver {
	
	/**
	 * If you want your setter to be non-public, you can specify an alternate access level here.
	 * 
	 * @return The setter method will be generated with this access modifier.
	 */
	lombok.AccessLevel level() default lombok.AccessLevel.PUBLIC;
	
	/**
	 * 
	 * @return
	 */
	boolean resolveMethodsIfFieldsListIsNullOrEmpty() default true;
	
	boolean addJsonIgnoreOnFieldsList() default true;
	
	boolean buildAdicionalFieldsListStringArrayConstructor() default true;
}