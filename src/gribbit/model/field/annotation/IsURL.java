/**
 * This file is part of the Gribbit Web Framework.
 * 
 *     https://github.com/lukehutch/gribbit
 * 
 * @author Luke Hutchison
 * 
 * --
 * 
 * @license Apache 2.0 
 * 
 * Copyright 2015 Luke Hutchison
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gribbit.model.field.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Used on fields of DataModel objects, where the field is substituted (via a template parameter with the same name as
 * the field) into an attribute of a custom elements, where the attribute expects a URL as a value.
 * 
 * For example, this attribute should be used in the following example:
 * 
 * <code>
 * 
 *     public class UserBadge extends DataModel {
 *         public String name;
 *   
 *         @ IsURL                 // Had to insert a space after '@' so that Eclipse code formatter doesn't freak out
 *         public String photoURL;
 *   
 *         public static final _template = "<user-badge name='${name}' photourl='${photoURL}'></user-badge>"; 
 *     }
 * 
 * </code>
 * 
 * Using this annotation on fields that are bound to URL-typepd parameter templates that are substituted into the
 * attributes of custom elements is useful for two reasons:
 * 
 * (1) Safety against XSS: Gribbit follows the OWASP recommendations to protect against XSS attacks by checking that all
 * URLs are correctly formatted before submitting into URL-typed attributes.
 * 
 * (2) Hash-caching: attributes that accept local URLs can be infinitely cached by hashing the contents of the resource
 * served at that URL. For custom elements, there is no way to know which values substituted into the custom elements
 * correspond to URLs, so this hash-caching cannot happen transparently, and the browser is left to request the resource
 * (or at least check its modification time) every time it is linked.
 * 
 * Note that this attribute is not used to check that the value of the annotated field is a valid URL during binding
 * from a POST request or before writing an object to the database, i.e. it is not a constraint annotation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface IsURL {
}
