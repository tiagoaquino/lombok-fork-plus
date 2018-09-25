import java.util.List;
public class SuperBuilderWithGenerics2 {
	public static class Parent<A> {
		A field1;
		List<String> items;
		@java.lang.SuppressWarnings("all")
		public static abstract class ParentBuilder<A, C extends Parent<A>, B extends ParentBuilder<A, C, B>> {
			@java.lang.SuppressWarnings("all")
			private A field1;
			@java.lang.SuppressWarnings("all")
			private java.util.ArrayList<String> items;
			@java.lang.SuppressWarnings("all")
			protected abstract B self();
			@java.lang.SuppressWarnings("all")
			public abstract C build();
			@java.lang.SuppressWarnings("all")
			public B field1(final A field1) {
				this.field1 = field1;
				return self();
			}
			@java.lang.SuppressWarnings("all")
			public B item(final String item) {
				if (this.items == null) this.items = new java.util.ArrayList<String>();
				this.items.add(item);
				return self();
			}
			@java.lang.SuppressWarnings("all")
			public B items(final java.util.Collection<? extends String> items) {
				if (this.items == null) this.items = new java.util.ArrayList<String>();
				this.items.addAll(items);
				return self();
			}
			@java.lang.SuppressWarnings("all")
			public B clearItems() {
				if (this.items != null) this.items.clear();
				return self();
			}
			@java.lang.Override
			@java.lang.SuppressWarnings("all")
			public java.lang.String toString() {
				return "SuperBuilderWithGenerics2.Parent.ParentBuilder(field1=" + this.field1 + ", items=" + this.items + ")";
			}
		}
		@java.lang.SuppressWarnings("all")
		private static final class ParentBuilderImpl<A> extends ParentBuilder<A, Parent<A>, ParentBuilderImpl<A>> {
			@java.lang.SuppressWarnings("all")
			private ParentBuilderImpl() {
			}
			@java.lang.Override
			@java.lang.SuppressWarnings("all")
			protected ParentBuilderImpl self() {
				return this;
			}
			@java.lang.Override
			@java.lang.SuppressWarnings("all")
			public Parent<A> build() {
				return new Parent<A>(this);
			}
		}
		@java.lang.SuppressWarnings("all")
		protected Parent(final ParentBuilder<A, ?, ?> b) {
			this.field1 = b.field1;
			java.util.List<String> items;
			switch (b.items == null ? 0 : b.items.size()) {
			case 0: 
				items = java.util.Collections.emptyList();
				break;
			case 1: 
				items = java.util.Collections.singletonList(b.items.get(0));
				break;
			default: 
				items = java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(b.items));
			}
			this.items = items;
		}
		@java.lang.SuppressWarnings("all")
		public static <A> ParentBuilder<A, ?, ?> builder() {
			return new ParentBuilderImpl<A>();
		}
	}
	public static class Child<A> extends Parent<String> {
		A field3;
		@java.lang.SuppressWarnings("all")
		public static abstract class ChildBuilder<A, C extends Child<A>, B extends ChildBuilder<A, C, B>> extends Parent.ParentBuilder<String, C, B> {
			@java.lang.SuppressWarnings("all")
			private A field3;
			@java.lang.Override
			@java.lang.SuppressWarnings("all")
			protected abstract B self();
			@java.lang.Override
			@java.lang.SuppressWarnings("all")
			public abstract C build();
			@java.lang.SuppressWarnings("all")
			public B field3(final A field3) {
				this.field3 = field3;
				return self();
			}
			@java.lang.Override
			@java.lang.SuppressWarnings("all")
			public java.lang.String toString() {
				return "SuperBuilderWithGenerics2.Child.ChildBuilder(super=" + super.toString() + ", field3=" + this.field3 + ")";
			}
		}
		@java.lang.SuppressWarnings("all")
		private static final class ChildBuilderImpl<A> extends ChildBuilder<A, Child<A>, ChildBuilderImpl<A>> {
			@java.lang.SuppressWarnings("all")
			private ChildBuilderImpl() {
			}
			@java.lang.Override
			@java.lang.SuppressWarnings("all")
			protected ChildBuilderImpl self() {
				return this;
			}
			@java.lang.Override
			@java.lang.SuppressWarnings("all")
			public Child<A> build() {
				return new Child<A>(this);
			}
		}
		@java.lang.SuppressWarnings("all")
		protected Child(final ChildBuilder<A, ?, ?> b) {
			super(b);
			this.field3 = b.field3;
		}
		@java.lang.SuppressWarnings("all")
		public static <A> ChildBuilder<A, ?, ?> builder2() {
			return new ChildBuilderImpl<A>();
		}
	}
	public static void test() {
		Child<Integer> x = Child.<Integer>builder2().field3(1).field1("value").item("").build();
	}
}
