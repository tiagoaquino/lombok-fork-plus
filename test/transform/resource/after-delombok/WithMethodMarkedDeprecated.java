class WithMethodMarkedDeprecated {
	@Deprecated
	int annotation;
	/**
	 * @deprecated
	 */
	int javadoc;
	WithMethodMarkedDeprecated(int annotation, int javadoc) {
	}
	@java.lang.Deprecated
	@java.lang.SuppressWarnings("all")
	public WithMethodMarkedDeprecated withAnnotation(final int annotation) {
		return this.annotation == annotation ? this : new WithMethodMarkedDeprecated(annotation, this.javadoc);
	}
	/**
	 * @deprecated
	 */
	@java.lang.Deprecated
	@java.lang.SuppressWarnings("all")
	public WithMethodMarkedDeprecated withJavadoc(final int javadoc) {
		return this.javadoc == javadoc ? this : new WithMethodMarkedDeprecated(this.annotation, javadoc);
	}
}
