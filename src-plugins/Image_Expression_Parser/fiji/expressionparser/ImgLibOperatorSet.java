package fiji.expressionparser;

import mpicbg.imglib.type.numeric.RealType;

import org.nfunk.jep.Operator;
import org.nfunk.jep.OperatorSet;

import fiji.expressionparser.function.ImgLibAdd;
import fiji.expressionparser.function.ImgLibComparison;
import fiji.expressionparser.function.ImgLibDivide;
import fiji.expressionparser.function.ImgLibMultiply;
import fiji.expressionparser.function.ImgLibSubtract;

public class ImgLibOperatorSet <T extends RealType<T>> extends OperatorSet {

	public ImgLibOperatorSet() {
		super();
		OP_ADD 			= new Operator("+", new ImgLibAdd<T>());
		OP_MULTIPLY    	= new Operator("*",new ImgLibMultiply<T>());
		OP_SUBTRACT 	= new Operator("-", new ImgLibSubtract<T>());
		OP_DIVIDE 		= new Operator("/", new ImgLibDivide<T>());
		OP_GE 			= new Operator(">=", new ImgLibComparison.GreaterOrEqual<T>());
		OP_GT			= new Operator(">", new ImgLibComparison.GreaterThan<T>());
		OP_LE 			= new Operator(">=", new ImgLibComparison.LowerOrEqual<T>());
		OP_LT			= new Operator(">", new ImgLibComparison.LowerThan<T>());
		OP_EQ			= new Operator("==", new ImgLibComparison.Equal<T>());
		OP_NE			= new Operator("!=", new ImgLibComparison.NotEqual<T>());
	}

}
